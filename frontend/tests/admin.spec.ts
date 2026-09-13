import { test, expect, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import type { Resource } from '../src/types/admin'

const apiKey = process.env.GATEWAY_ADMIN_API_KEY ?? process.env.GATEWAY_API_KEY ?? 'dev-gateway-key'
const prefix = 'e2e-' + Date.now().toString(36)

async function selectOption(page: Page, label: string, option: string) {
  await page.getByRole('dialog').getByLabel(label, { exact: true }).press('ArrowDown')
  await page.getByRole('option', { name: option, exact: true }).click()
}

async function save(page: Page) {
  await page.getByRole('dialog').getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
}

async function adminRows(request: APIRequestContext, resource: Resource) {
  const response = await request.get('/api/admin/' + resource, { headers: { Authorization: 'Bearer ' + apiKey } })
  expect(response.ok()).toBeTruthy()
  return response.json()
}

async function remove(page: Page, row: Locator, label: string) {
  await row.getByRole('button', { name: label, exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click()
  await expect(row).toHaveCount(0)
}

test('manages the configuration graph and provider actions through the admin UI', async ({ page, request }, testInfo) => {
  const failures: string[] = []
  page.on('pageerror', error => failures.push(error.message))
  let catalogStatus = 200
  const catalog = createServer((incoming, outgoing) => {
    if (incoming.headers.authorization !== 'Bearer fixture-provider-key') {
      outgoing.writeHead(401).end()
      return
    }
    outgoing.writeHead(catalogStatus, { 'Content-Type': 'application/json' })
    outgoing.end(catalogStatus === 200 ? JSON.stringify({ data: [{ id: prefix + '-discovered' }] }) : '{"error":"fixture-provider-key"}')
  })
  await new Promise<void>(resolve => catalog.listen(0, '0.0.0.0', resolve))
  const catalogUrl = 'http://' + (process.env.PROVIDER_FIXTURE_HOST ?? '127.0.0.1') + ':' + (catalog.address() as AddressInfo).port
  const providerIds = new Set<string>()
  const modelIds = new Set<string>()
  const virtualIds = new Set<string>()
  try {
    await page.goto('/')
    await page.getByRole('dialog').getByRole('textbox', { name: 'Admin API key' }).fill(apiKey)
    await page.getByRole('button', { name: 'Connect', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await page.getByRole('menuitem', { name: 'Providers', exact: true }).click()
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    await page.getByLabel('Name', { exact: true }).fill(prefix + '-provider')
    await page.getByLabel('Base URL', { exact: true }).fill(catalogUrl)
    await page.getByLabel('Provider API key', { exact: true }).fill('fixture-provider-key')
    await save(page)
    const providerRow = page.getByRole('row').filter({ hasText: prefix + '-provider' })
    await expect(providerRow).toHaveCount(1)
    await providerRow.getByRole('button', { name: 'Edit provider', exact: true }).click()
    await expect(page.getByLabel('Provider API key', { exact: true })).toHaveValue('')
    await page.getByLabel('Name', { exact: true }).fill(prefix + '-provider-edited')
    await save(page)
    await providerRow.locator('.el-switch').click()
    await expect(providerRow.getByRole('switch')).not.toBeChecked()
    await providerRow.locator('.el-switch').click()
    await expect(providerRow.getByRole('switch')).toBeChecked()
    await providerRow.getByRole('button', { name: 'Test connection', exact: true }).click()
    await expect(page.getByText(/Connected: 1 models/)).toBeVisible()
    await providerRow.getByRole('button', { name: 'Sync models', exact: true }).click()
    await expect(page.getByText('Sync complete: 1 added, 0 updated')).toBeVisible()
    await providerRow.getByRole('button', { name: 'Sync models', exact: true }).click()
    await expect(page.getByText('Sync complete: 0 added, 1 updated')).toBeVisible()
    catalogStatus = 503
    await providerRow.getByRole('button', { name: 'Sync models', exact: true }).click()
    await expect(page.getByText('Provider model catalog returned HTTP 503')).toBeVisible()
    await expect(page.getByText('fixture-provider-key', { exact: true })).toHaveCount(0)
    catalogStatus = 200
    await expect(page.getByText('Provider model catalog returned HTTP 503')).toHaveCount(0)
    await expect(page.locator('.el-message')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('providers.png'), fullPage: true })

    await page.getByRole('menuitem', { name: 'Provider models', exact: true }).click()
    const discoveredRow = page.getByRole('row').filter({ hasText: prefix + '-discovered' })
    await expect(discoveredRow).toHaveCount(1)
    await discoveredRow.getByRole('button', { name: 'Enable', exact: true }).click()
    await expect(discoveredRow).toContainText('ACTIVE')
    await discoveredRow.getByRole('button', { name: 'Disable', exact: true }).click()
    await expect(discoveredRow).toContainText('DISABLED')
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    await selectOption(page, 'Provider', prefix + '-provider-edited')
    await page.getByLabel('Model name', { exact: true }).fill(prefix + '-manual')
    await page.getByLabel('Display name', { exact: true }).fill('Manual model')
    await selectOption(page, 'Status', 'ACTIVE')
    await save(page)
    const modelRow = page.getByRole('row').filter({ hasText: prefix + '-manual' })
    await modelRow.getByRole('button', { name: 'Edit provider model', exact: true }).click()
    await page.getByLabel('Display name', { exact: true }).fill('Edited model')
    await save(page)
    await expect(modelRow).toContainText('Edited model')
    await expect(page.locator('.el-message')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('provider-models.png'), fullPage: true })

    await page.getByRole('menuitem', { name: 'Virtual models', exact: true }).click()
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    await page.getByLabel('Name', { exact: true }).fill(prefix + '-public')
    await save(page)
    const virtualRow = page.getByRole('row').filter({ hasText: prefix + '-public' })
    await virtualRow.getByRole('button', { name: 'Edit virtual model', exact: true }).click()
    await page.getByLabel('Description', { exact: true }).fill('Edited through the browser')
    await save(page)
    await expect(virtualRow).toContainText('Edited through the browser')
    await virtualRow.locator('.el-switch').click()
    await expect(virtualRow.getByRole('switch')).not.toBeChecked()
    await virtualRow.locator('.el-switch').click()
    await expect(virtualRow.getByRole('switch')).toBeChecked()

    await page.getByRole('menuitem', { name: 'Bindings', exact: true }).click()
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    await selectOption(page, 'Virtual model', prefix + '-public')
    await selectOption(page, 'Provider', prefix + '-provider-edited')
    await selectOption(page, 'Provider model', prefix + '-manual (ACTIVE)')
    await save(page)
    const bindingRow = page.getByRole('row').filter({ hasText: prefix + '-public' })
    await expect(bindingRow).toContainText(prefix + '-manual')
    await bindingRow.getByRole('button', { name: 'Edit binding', exact: true }).click()
    await page.getByLabel('Priority', { exact: true }).fill('42')
    await save(page)
    await expect(bindingRow).toContainText('42')
    await bindingRow.locator('.el-switch').click()
    await expect(bindingRow.getByRole('switch')).not.toBeChecked()
    await bindingRow.locator('.el-switch').click()
    await expect(bindingRow.getByRole('switch')).toBeChecked()
    await expect(page.locator('.el-message')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('bindings.png'), fullPage: true })
    await page.reload()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await page.getByRole('menuitem', { name: 'Bindings', exact: true }).click()
    await expect(bindingRow).toContainText(prefix + '-manual')
    await remove(page, bindingRow, 'Delete binding')
    await page.getByRole('menuitem', { name: 'Provider models', exact: true }).click()
    await remove(page, modelRow, 'Delete provider model')
    await remove(page, discoveredRow, 'Delete provider model')
    await page.getByRole('menuitem', { name: 'Virtual models', exact: true }).click()
    await remove(page, virtualRow, 'Delete virtual model')
    await page.getByRole('menuitem', { name: 'Providers', exact: true }).click()
    await remove(page, providerRow, 'Delete provider')
    expect(failures).toEqual([])
  } finally {
    for (const provider of await adminRows(request, 'providers')) if (provider.name.startsWith(prefix)) providerIds.add(provider.id)
    for (const model of await adminRows(request, 'provider-models')) if (providerIds.has(model.providerId)) modelIds.add(model.id)
    for (const model of await adminRows(request, 'virtual-models')) if (model.name.startsWith(prefix)) virtualIds.add(model.id)
    const headers = { Authorization: 'Bearer ' + apiKey }
    for (const binding of await adminRows(request, 'bindings')) {
      if (providerIds.has(binding.providerId)) await request.delete('/api/admin/bindings/' + binding.id, { headers })
    }
    for (const id of modelIds) await request.delete('/api/admin/provider-models/' + id, { headers })
    for (const id of virtualIds) await request.delete('/api/admin/virtual-models/' + id, { headers })
    for (const id of providerIds) await request.delete('/api/admin/providers/' + id, { headers })
    await new Promise<void>((resolve, reject) => catalog.close(error => error ? reject(error) : resolve()))
  }
})

test('closes a saved form when refreshing fails and recovers without creating a duplicate', async ({ page, request }) => {
  const name = prefix + '-refresh-failure'
  let failRefresh = false
  await page.route('**/api/admin/providers', async route => {
    if (failRefresh && route.request().method() === 'GET') {
      await route.fulfill({ status: 503, json: { message: 'Refresh unavailable' } })
    } else {
      await route.continue()
    }
  })
  try {
    await page.goto('/')
    await page.getByRole('dialog').getByLabel('Admin API key', { exact: true }).fill(apiKey)
    await page.getByRole('button', { name: 'Connect', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await page.getByRole('menuitem', { name: 'Providers', exact: true }).click()
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    await page.getByLabel('Name', { exact: true }).fill(name)
    await page.getByLabel('Base URL', { exact: true }).fill('http://localhost:9000')
    failRefresh = true
    await save(page)
    await expect(page.getByText('Refresh unavailable', { exact: true })).toBeVisible()
    const providers = await adminRows(request, 'providers')
    expect(providers.filter((provider: { name: string }) => provider.name === name)).toHaveLength(1)
    failRefresh = false
    await page.getByRole('button', { name: 'Refresh', exact: true }).click()
    await expect(page.getByRole('row').filter({ hasText: name })).toHaveCount(1)
    await expect(page.getByText('Refresh unavailable', { exact: true })).toHaveCount(0)
  } finally {
    for (const provider of await adminRows(request, 'providers')) {
      if (provider.name === name) {
        await request.delete('/api/admin/providers/' + provider.id, { headers: { Authorization: 'Bearer ' + apiKey } })
      }
    }
  }
})
