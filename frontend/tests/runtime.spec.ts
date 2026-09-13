import { test, expect, type APIRequestContext, type Page, type TestInfo } from '@playwright/test'
import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'

const apiKey = process.env.GATEWAY_API_KEY ?? 'dev-gateway-key'
const headers = { Authorization: 'Bearer ' + apiKey }
const prefix = 'runtime-e2e-' + Date.now().toString(36)
const providerKey = 'synthetic-runtime-provider-key'

async function rows(request: APIRequestContext, resource: string) {
  const response = await request.get('/api/admin/' + resource, { headers })
  expect(response.ok()).toBeTruthy()
  return response.json()
}
async function create(request: APIRequestContext, resource: string, data: object) {
  const response = await request.post('/api/admin/' + resource, { headers, data })
  expect(response.ok(), await response.text()).toBeTruthy()
  return response.json()
}
async function connect(page: Page) {
  await page.goto('/')
  await page.getByRole('dialog').getByRole('textbox', { name: 'Admin API key', exact: true }).fill(apiKey)
  await page.getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
}
async function select(page: Page, label: string, option: string) {
  await page.getByRole('dialog').getByLabel(label, { exact: true }).press('ArrowDown')
  await page.getByRole('option', { name: option, exact: true }).click()
}
async function save(page: Page) {
  await page.getByRole('dialog').getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
}
async function capture(page: Page, testInfo: TestInfo, name: string) {
  await expect(page.locator('.el-message')).toHaveCount(0, { timeout: 10000 })
  await page.screenshot({ path: testInfo.outputPath(name), fullPage: true, animations: 'disabled' })
}
async function infer(request: APIRequestContext, model: string, status = 200) {
  const response = await request.post('/v1/chat/completions', { headers, data: { model, messages: [{ role: 'user', content: 'Synthetic browser verification' }] } })
  expect(response.status()).toBe(status)
  const body = await response.json()
  expect(JSON.stringify(body)).not.toContain(providerKey)
  return body
}

test('routing configuration controls inference and exposes health and discovery recovery', async ({ page, request }, testInfo) => {
  test.setTimeout(120000)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const realNames = [prefix + '-real', prefix + '-other']
  let catalogNames = [...realNames]
  let catalogStatus = 200
  let inferenceStatus = 200
  let inferenceCalls = 0
  const fixture = createServer(async (incoming, outgoing) => {
    if (incoming.headers.authorization !== 'Bearer ' + providerKey) { outgoing.writeHead(401).end(); return }
    outgoing.setHeader('Content-Type', 'application/json')
    if (incoming.url?.endsWith('/models')) {
      outgoing.writeHead(catalogStatus).end(JSON.stringify(catalogStatus === 200 ? { data: catalogNames.map(id => ({ id })) } : { error: providerKey }))
      return
    }
    inferenceCalls++
    const chunks: Buffer[] = []
    for await (const chunk of incoming) chunks.push(Buffer.from(chunk))
    const input = JSON.parse(Buffer.concat(chunks).toString())
    outgoing.writeHead(inferenceStatus).end(JSON.stringify(inferenceStatus === 200
      ? { id: 'synthetic-browser-response', model: input.model, choices: [{ index: 0, message: { role: 'assistant', content: 'served:' + input.model }, finish_reason: 'stop' }], usage: { prompt_tokens: 3, completion_tokens: 2, total_tokens: 5 } }
      : { error: providerKey }))
  })
  await new Promise<void>(resolve => fixture.listen(0, '0.0.0.0', resolve))
  const fixtureUrl = 'http://' + (process.env.PROVIDER_FIXTURE_HOST ?? '127.0.0.1') + ':' + (fixture.address() as AddressInfo).port
  const ids: Record<string, string[]> = { providers: [], 'provider-models': [], 'virtual-models': [], bindings: [], 'model-rules': [], 'routing-policies': [] }
  async function add(resource: string, data: object) {
    const result = await create(request, resource, data); ids[resource].push(result.id); return result
  }
  try {
    const provider = await add('providers', { name: prefix + '-provider', baseUrl: fixtureUrl, apiKey: providerKey, protocol: 'CHAT_COMPLETIONS', modelDiscoveryEnabled: false, modelDiscoveryIntervalMs: 1000 })
    const models = []
    const virtuals = []
    for (let i = 0; i < 2; i++) {
      models.push(await add('provider-models', { providerId: provider.id, modelName: realNames[i], status: 'ACTIVE', capabilities: '["CHAT","STREAMING"]' }))
      virtuals.push(await add('virtual-models', { name: prefix + (i ? '-alternate' : '-public'), enabled: true }))
      await add('bindings', { virtualModelId: virtuals[i].id, providerId: provider.id, providerModelId: models[i].id, sourceProtocol: 'CHAT_COMPLETIONS', targetProtocol: 'CHAT_COMPLETIONS' })
    }
    // Compare lifecycle metadata at database precision, not the nanosecond create response.
    const persistedModel = (await rows(request, 'provider-models')).find((model: { id: string }) => model.id === models[0].id)
    expect(persistedModel.firstSeenAt).toBeTruthy()
    await connect(page)
    await page.getByRole('menuitem', { name: 'Routing policies', exact: true }).click()
    await page.getByRole('button', { name: 'Add policy', exact: true }).click()
    await page.getByLabel('Policy name', { exact: true }).fill(prefix + '-policy')
    await page.getByLabel('Circuit failure threshold', { exact: true }).fill('2')
    await page.getByLabel('Circuit cooldown (ms)', { exact: true }).fill('30000')
    await save(page)
    const policy = (await rows(request, 'routing-policies')).find((p: { name: string }) => p.name === prefix + '-policy')
    ids['routing-policies'].push(policy.id)
    await capture(page, testInfo, 'routing-policies.png')

    await page.getByRole('menuitem', { name: 'Virtual models', exact: true }).click()
    await page.getByRole('row').filter({ hasText: virtuals[0].name }).getByRole('button', { name: 'Edit virtual model', exact: true }).click()
    await select(page, 'Routing policy', policy.name + ' (PRIORITY)')
    await save(page)
    await page.getByRole('menuitem', { name: 'Model rules', exact: true }).click()
    await page.getByRole('button', { name: 'Add rule', exact: true }).click()
    await page.getByLabel('Pattern', { exact: true }).fill(prefix + '-alias-*')
    await select(page, 'Target virtual model', virtuals[0].name)
    await save(page)
    let rule = (await rows(request, 'model-rules')).find((r: { pattern: string }) => r.pattern === prefix + '-alias-*')
    ids['model-rules'].push(rule.id)
    expect((await infer(request, prefix + '-alias-one')).choices[0].message.content).toBe('served:' + realNames[0])
    const ruleRow = page.getByRole('row').filter({ hasText: rule.pattern })
    await ruleRow.getByRole('button', { name: 'Edit rule', exact: true }).click()
    await select(page, 'Target virtual model', virtuals[1].name)
    await save(page)
    expect((await infer(request, prefix + '-alias-two')).choices[0].message.content).toBe('served:' + realNames[1])
    const stale = await request.put('/api/admin/model-rules/' + rule.id, { headers, data: rule })
    expect(stale.status()).toBe(409)
    rule = (await rows(request, 'model-rules')).find((r: { id: string }) => r.id === rule.id)
    await page.getByRole('button', { name: 'Add rule', exact: true }).click()
    await page.getByLabel('Pattern', { exact: true }).fill(rule.pattern)
    await select(page, 'Target virtual model', virtuals[0].name)
    await page.getByRole('dialog').getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByRole('dialog')).toContainText('ambiguous target')
    await page.getByRole('dialog').getByRole('button', { name: 'Close this dialog', exact: true }).click()
    await capture(page, testInfo, 'model-rules.png')

    await page.getByRole('menuitem', { name: 'Routing policies', exact: true }).click()
    await page.getByRole('row').filter({ hasText: policy.name }).getByRole('button', { name: 'Delete policy', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click()
    await expect(page.getByText(/Configuration conflicts with an existing record or reference/)).toBeVisible()
    await page.getByRole('menuitem', { name: 'Routing preview', exact: true }).click()
    const callsBefore = inferenceCalls
    await page.getByLabel('Requested model', { exact: true }).fill(prefix + '-alias-two')
    await page.getByRole('button', { name: 'Preview route', exact: true }).click()
    await expect(page.getByText('Resolved model:', { exact: false })).toContainText(virtuals[1].name)
    await expect(page.getByRole('row').filter({ hasText: realNames[1] })).toContainText('ELIGIBLE')
    await page.getByRole('button', { name: 'Check saved configuration', exact: true }).click()
    await expect(page.getByText('Saved configuration has no compatibility issues.')).toBeVisible()
    expect(inferenceCalls).toBe(callsBefore)
    await capture(page, testInfo, 'routing-preview.png')

    inferenceStatus = 503
    await infer(request, virtuals[0].name, 502); await infer(request, virtuals[0].name, 502)
    await page.getByRole('menuitem', { name: 'Health', exact: true }).click()
    const healthRow = page.getByRole('row').filter({ hasText: virtuals[0].name })
    await expect(healthRow).toContainText('OPEN')
    await expect(healthRow).toContainText('CIRCUIT_UNAVAILABLE')
    await expect(healthRow.locator('.el-tag')).toHaveText('UNKNOWN')
    await healthRow.locator('.el-table__expand-icon').click()
    await expect(page.getByText('Success rate: 33.3%', { exact: false })).toBeVisible()
    await expect(page.locator('body')).not.toContainText(providerKey)
    await capture(page, testInfo, 'health.png')
    inferenceStatus = 200

    await page.getByRole('menuitem', { name: 'Providers', exact: true }).click()
    await page.getByRole('row').filter({ hasText: provider.name }).getByRole('button', { name: 'Edit provider', exact: true }).click()
    await page.getByText('Automatic model discovery', { exact: true }).click()
    await page.getByLabel('Enable automatic discovery', { exact: true }).locator('..').click()
    await save(page)
    const discoveryState = async () => (await rows(request, 'discovery')).providers.find((p: { providerId: string }) => p.providerId === provider.id)?.status
    await expect.poll(async () => (await discoveryState())?.state, { timeout: 15000 }).toBe('SUCCESS')
    catalogStatus = 503
    await expect.poll(async () => (await discoveryState())?.state, { timeout: 15000 }).toBe('FAILED')
    await page.getByRole('menuitem', { name: 'Model discovery', exact: true }).click()
    const discoveryRow = page.getByRole('row').filter({ hasText: provider.name })
    await expect(discoveryRow).toContainText('FAILED')
    await discoveryRow.locator('.el-table__expand-icon').click()
    await expect(page.getByText('Last error: PROVIDER_HTTP_ERROR')).toBeVisible()
    catalogNames = []; catalogStatus = 200
    await expect.poll(async () => (await rows(request, 'provider-models')).find((m: { id: string }) => m.id === models[0].id)?.status, { timeout: 15000 }).toBe('REMOVED')
    await page.getByRole('menuitem', { name: 'Provider models', exact: true }).click()
    await page.getByRole('button', { name: 'Refresh', exact: true }).click()
    await expect(page.getByRole('row').filter({ hasText: realNames[0] })).toContainText('Removed by discovery')
    catalogNames = [...realNames]
    await expect.poll(async () => (await rows(request, 'provider-models')).find((m: { id: string }) => m.id === models[0].id)?.status, { timeout: 15000 }).toBe('ACTIVE')
    const restored = (await rows(request, 'provider-models')).find((m: { id: string }) => m.id === models[0].id)
    expect(restored.firstSeenAt).toBe(persistedModel.firstSeenAt)
    expect(JSON.parse(restored.capabilities)).toEqual(['CHAT', 'STREAMING'])
    const currentProvider = (await rows(request, 'providers')).find((p: { id: string }) => p.id === provider.id)
    expect((await request.put('/api/admin/providers/' + provider.id, { headers, data: { ...currentProvider, modelDiscoveryEnabled: false } })).ok()).toBeTruthy()
    await page.getByRole('button', { name: 'Refresh', exact: true }).click()
    await page.getByRole('menuitem', { name: 'Model discovery', exact: true }).click()
    await expect(discoveryRow).toContainText('SUCCESS')
    await capture(page, testInfo, 'model-discovery.png')
    expect((await infer(request, virtuals[0].name)).choices[0].message.content).toBe('served:' + realNames[0])
    await page.getByRole('menuitem', { name: 'Overview', exact: true }).click()
    await expect(page.getByText('Requests per minute', { exact: true })).toBeVisible()
    await expect(page.locator('body')).not.toContainText(providerKey)
    await capture(page, testInfo, 'dashboard.png')
    expect(errors).toEqual([])
    expect(JSON.stringify(await rows(request, 'providers'))).not.toContain(providerKey)
  } finally {
    for (const provider of await rows(request, 'providers')) {
      if (ids.providers.includes(provider.id) && provider.modelDiscoveryEnabled) {
        await request.put('/api/admin/providers/' + provider.id, { headers, data: { ...provider, modelDiscoveryEnabled: false } })
      }
    }
    // Discover records created by UI before a possible assertion failure, then remove only this test's graph.
    for (const resource of ['model-rules', 'routing-policies']) {
      for (const item of await rows(request, resource)) {
        if ((item.pattern ?? item.name)?.startsWith(prefix) && !ids[resource].includes(item.id)) ids[resource].push(item.id)
      }
    }
    for (const resource of ['bindings', 'model-rules', 'virtual-models', 'provider-models', 'routing-policies', 'providers']) {
      for (const id of ids[resource]) await request.delete('/api/admin/' + resource + '/' + id, { headers })
    }
    fixture.closeAllConnections()
    await new Promise<void>((resolve, reject) => fixture.close(error => error ? reject(error) : resolve()))
  }
})

test('runtime failures leave configuration usable and polling stops when leaving the page', async ({ page }) => {
  let healthCalls = 0
  await page.route('**/api/admin/health', async route => {
    healthCalls++
    await route.fulfill({ status: 503, json: { message: 'Synthetic runtime unavailable' } })
  })
  await connect(page)
  await page.getByRole('menuitem', { name: 'Health', exact: true }).click()
  await expect(page.getByText(/Synthetic runtime unavailable/)).toBeVisible()
  await page.getByRole('menuitem', { name: 'Providers', exact: true }).click()
  await page.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('button', { name: 'Cancel', exact: true }).click()
  const count = healthCalls
  await page.waitForTimeout(3300)
  expect(healthCalls).toBe(count)
})
