import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { performance } from 'node:perf_hooks'

// Run against a dedicated test stack. All provider requests stay on this local fixture.
const gateway = process.env.GATEWAY_URL ?? 'http://127.0.0.1:18090'
const gatewayKey = process.env.GATEWAY_API_KEY
const adminKey = process.env.GATEWAY_ADMIN_API_KEY ?? gatewayKey
assert(gatewayKey, 'Set GATEWAY_API_KEY for the isolated verification stack')
const providerKey = 'synthetic-bounded-load-provider-key'
const prefix = 'bounded-' + randomUUID().slice(0, 8)
const records = []
const sockets = new Set()
const cancelledAt = new Map()
const cancellationDelays = []
const phasePeaks = {}
let phase = 'warmup'
let active = 0
let peakConnections = 0
let calls = 0
const frame = (id, delta, finish = null) => 'data: ' + JSON.stringify({ id, choices: [{ index: 0, delta, finish_reason: finish }] }) + '\n\n'
const fixture = createServer(async (request, response) => {
  if (request.headers.authorization !== 'Bearer ' + providerKey) { response.writeHead(401).end(); return }
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  const body = JSON.parse(Buffer.concat(chunks).toString())
  const requestId = body.messages[0].content
  const currentPhase = phase
  const id = 'fixture-' + ++calls
  active++
  phasePeaks[currentPhase] = Math.max(phasePeaks[currentPhase] ?? 0, active)
  let timer
  let ended = false
  response.once('close', () => {
    active--; clearInterval(timer); clearTimeout(timer)
    if (!ended && cancelledAt.has(requestId)) cancellationDelays.push(performance.now() - cancelledAt.get(requestId))
  })
  if (!body.stream) {
    timer = setTimeout(() => {
      ended = true
      response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ id, model: body.model,
        choices: [{ index: 0, message: { role: 'assistant', content: 'synthetic complete answer' }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 4, completion_tokens: 2, total_tokens: 6 } }))
    }, currentPhase === 'timeout' ? 600 : 20)
    return
  }
  response.writeHead(200, { 'Content-Type': 'text/event-stream' })
  response.write(': synthetic heartbeat\n\n' + frame(id, { role: 'assistant' }))
  let tokens = 0
  timer = setInterval(() => {
    response.write(frame(id, { content: 'x'.repeat(1024) }))
    if (++tokens === 40 && currentPhase !== 'cancel') {
      clearInterval(timer); ended = true
      response.end(frame(id, {}, 'stop') + 'data: [DONE]\n\n')
    }
  }, 20)
})
fixture.on('connection', socket => {
  sockets.add(socket); peakConnections = Math.max(peakConnections, sockets.size)
  socket.once('close', () => sockets.delete(socket))
})
await new Promise(resolve => fixture.listen(0, '0.0.0.0', resolve))
const fixtureUrl = 'http://' + (process.env.PROVIDER_FIXTURE_HOST ?? '127.0.0.1') + ':' + fixture.address().port

async function api(path, method = 'GET', body) {
  const response = await fetch(gateway + '/api/admin/' + path, {
    method, headers: { Authorization: 'Bearer ' + adminKey, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000)
  })
  assert(response.ok, 'Administration returned HTTP ' + response.status)
  return response.status === 204 ? undefined : response.json()
}
async function create(resource, body) {
  const result = await api(resource, 'POST', body)
  records.push([resource, result.id]); return result
}
async function infer(model, streaming, requestId, signal) {
  return fetch(gateway + '/v1/chat/completions', {
    method: 'POST', headers: { Authorization: 'Bearer ' + gatewayKey, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, stream: streaming, messages: [{ role: 'user', content: requestId }] }),
    signal: signal ?? AbortSignal.timeout(10000)
  })
}
async function concurrent(count, concurrency, work) {
  let next = 0
  const times = []
  await Promise.all(Array.from({ length: concurrency }, async () => {
    for (;;) {
      const index = next++
      if (index >= count) return
      const began = performance.now(); await work(index); times.push(performance.now() - began)
    }
  }))
  return times.sort((a, b) => a - b)
}
const summary = values => ({ count: values.length, p50Ms: +values[Math.ceil(values.length * 0.5) - 1].toFixed(2),
  p95Ms: +values[Math.ceil(values.length * 0.95) - 1].toFixed(2), maxMs: +values.at(-1).toFixed(2) })
function samples(scrape, name, filter = () => true) {
  return scrape.split('\n').filter(line => (line.startsWith(name + '{') || line.startsWith(name + ' ')) && filter(line))
    .reduce((total, line) => total + Number(line.split(' ').at(-1)), 0)
}
async function metrics() {
  const response = await fetch(gateway + '/actuator/prometheus', { headers: { Authorization: 'Bearer ' + adminKey }, signal: AbortSignal.timeout(5000) })
  assert(response.ok, 'Metrics endpoint must be available to the verification key')
  const scrape = await response.text()
  return { requests: samples(scrape, 'llm_gateway_requests_total'), attempts: samples(scrape, 'llm_gateway_provider_attempts_total'),
    heapUsedBytes: samples(scrape, 'jvm_memory_used_bytes', line => line.includes('area="heap"')),
    heapMaxBytes: samples(scrape, 'jvm_memory_max_bytes', line => line.includes('area="heap"')) }
}

let polling = true
let collector
try {
  const provider = await create('providers', { name: prefix, baseUrl: fixtureUrl, protocol: 'CHAT_COMPLETIONS', apiKey: providerKey, modelDiscoveryEnabled: false })
  const model = await create('provider-models', { providerId: provider.id, modelName: 'synthetic-load-model', status: 'ACTIVE', capabilities: '["CHAT","STREAMING"]' })
  const virtual = await create('virtual-models', { name: prefix })
  await create('bindings', { virtualModelId: virtual.id, providerId: provider.id, providerModelId: model.id, sourceProtocol: 'CHAT_COMPLETIONS', targetProtocol: 'CHAT_COMPLETIONS' })
  await concurrent(8, 4, async i => { const response = await infer(prefix, false, 'warmup-' + i); assert.equal(response.status, 200); await response.json() })
  const before = await metrics()
  const beforeCalls = calls
  let peakHeap = before.heapUsedBytes
  collector = (async () => {
    while (polling) { const value = await metrics(); peakHeap = Math.max(peakHeap, value.heapUsedBytes); await delay(250) }
  })()
  phase = 'nonStreaming'
  const started = performance.now()
  const nonStreaming = await concurrent(64, 8, async i => {
    const response = await infer(prefix, false, 'bounded-' + i)
    assert.equal(response.status, 200); assert.equal((await response.json()).model, prefix)
  })
  const throughput = 64000 / (performance.now() - started)
  phase = 'slowStreaming'
  const slowStreaming = await concurrent(8, 4, async i => {
    const response = await infer(prefix, true, 'slow-' + i)
    assert.equal(response.status, 200)
    let bytes = 0
    let tail = ''
    for await (const chunk of response.body) {
      bytes += chunk.length; tail = (tail + Buffer.from(chunk).toString()).slice(-256)
      await delay(30)
    }
    assert(bytes >= 40 * 1024); assert(tail.includes('[DONE]'))
  })
  phase = 'cancel'
  await concurrent(4, 4, async i => {
    const id = 'cancel-' + i; const abort = new AbortController()
    const response = await infer(prefix, true, id, abort.signal)
    assert.equal(response.status, 200)
    const reader = response.body.getReader(); await reader.read()
    cancelledAt.set(id, performance.now()); abort.abort()
    await reader.cancel().catch(() => {})
  })
  const cancellationDeadline = performance.now() + 2000
  while (cancellationDelays.length < 4 && performance.now() < cancellationDeadline) await delay(20)
  assert.equal(cancellationDelays.length, 4, 'Every cancelled client must close its upstream stream within two seconds')
  phase = 'timeout'
  await api('providers/' + provider.id, 'PUT', { ...provider, requestTimeoutMs: 100 })
  const timeouts = await concurrent(2, 2, async i => {
    const response = await infer(prefix, false, 'timeout-' + i)
    assert.equal(response.status, 504); await response.json()
  })
  await delay(600)
  const after = await metrics()
  assert.equal(active, 0, 'No fixture request may remain active after cancellation and timeouts')
  assert.equal(calls - beforeCalls, 78, 'Fixture calls must equal the fixed logical request count without replay')
  assert.equal(after.attempts - before.attempts, 78, 'Attempt metrics must count physical calls exactly once')
  assert.equal(after.requests - before.requests, 78, 'Logical metrics must count completions, cancellations and timeouts')
  assert(nonStreaming.at(-1) < 5000 && nonStreaming[Math.ceil(64 * 0.95) - 1] < 3000)
  assert(slowStreaming.at(-1) < 10000)
  assert(peakConnections <= 16, 'The controlled load must not leak upstream connections')
  const report = {
    verifiedAt: new Date().toISOString(), result: 'passed', environment: { gateway: 'isolated Docker production profile', node: process.version,
      mysql: '8.4', redis: '7.4', jvmHeapLimitMiB: 512, reactorPoolMaxConnectionsPerHost: 64 },
    workload: { nonStreamingRequests: 64, concurrency: 8, fixtureDelayMs: 20, slowStreams: 8, streamConcurrency: 4,
      streamChunks: 40, chunkBytes: 1024, chunkIntervalMs: 20, consumerReadDelayMs: 30, cancellations: 4, timeouts: 2, providerTimeoutMs: 100 },
    thresholds: { nonStreamingP95Ms: 3000, nonStreamingMaxMs: 5000, slowStreamMaxMs: 10000, cancellationMaxMs: 2000, upstreamConnections: 16 },
    nonStreaming: { ...summary(nonStreaming), requestsPerSecond: +throughput.toFixed(2) }, slowStreaming: summary(slowStreaming),
    cancellation: summary(cancellationDelays.sort((a, b) => a - b)), timeouts: summary(timeouts),
    resources: { peakActiveRequestsByPhase: phasePeaks, peakUpstreamConnections: peakConnections, activeRequestsAtEnd: active,
      heapBeforeBytes: before.heapUsedBytes, heapPeakBytes: peakHeap, heapAfterBytes: after.heapUsedBytes, heapMaxBytes: after.heapMaxBytes },
    accounting: { logicalRequests: after.requests - before.requests, providerAttempts: after.attempts - before.attempts, fixtureCalls: calls - beforeCalls }
  }
  const output = process.env.VERIFICATION_REPORT ?? 'docs/verification/runtime-benchmark.json'
  await mkdir(dirname(output), { recursive: true }); await writeFile(output, JSON.stringify(report, null, 2) + '\n')
  console.log(JSON.stringify(report, null, 2))
} finally {
  polling = false
  await collector?.catch(() => {})
  for (const [resource, id] of records.reverse()) await api(resource + '/' + id, 'DELETE').catch(() => {})
  fixture.closeAllConnections()
  await new Promise(resolve => fixture.close(resolve))
}
