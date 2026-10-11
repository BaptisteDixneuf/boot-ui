import assert from 'node:assert/strict'
import {mock, test} from 'node:test'
import {CosmosClient, type OperationInput} from '@azure/cosmos'
import {HttpRequest, InvocationContext} from '@azure/functions'
import {emptyAggregate} from './feedback.js'
import {read, submit} from './index.js'

function fixture() {
  const client = new CosmosClient({endpoint: 'https://fixture.documents.azure.com', key: Buffer.alloc(32).toString('base64')})
  const storage = client.database('feedback').container('votes')
  const item = storage.item('aggregate', 'route-errors')
  mock.method(storage, 'item', () => item)
  mock.method(item, 'read', async () => ({resource: emptyAggregate('route-errors')}))
  const create = mock.method(storage.items, 'create', async () => ({}))
  const batch = mock.method(storage.items, 'batch', async (_operations: OperationInput[], _kind: string) => ({code: 200, result: [{statusCode: 201}, {statusCode: 200}]}))
  const context = new InvocationContext({functionName: 'test'})
  const errors = mock.method(context, 'error', () => {})
  const request = (body: string, headers: Record<string, string> = {'client-ip': '198.51.100.1'}) => new HttpRequest({
    method: 'POST', url: 'https://fixture.azurewebsites.net/api/vote',
    headers, body: {string: body}
  })
  return {storage, item, create, batch, context, errors, request}
}

test('vote handlers reject oversized, invalid, or unidentified input without storage', async () => {
  const f = fixture()
  const unavailableStore = () => { throw new Error('Storage must not be called') }
  for (const [body, status] of [
    ['x'.repeat(257), 413], ['{', 400], ['{"kind":"route-errors","stars":6,"findings":0}', 400]
  ] as const) {
    assert.equal((await submit(f.request(body), f.context, unavailableStore)).status, status)
  }
  assert.equal((await submit(f.request('{}', {}), f.context, unavailableStore)).status, 400)
  assert.equal(f.create.mock.callCount(), 0)
})

test('duplicates never produce success and the vote/counters share one partition batch', async () => {
  const f = fixture()
  f.create.mock.mockImplementation(async () => { throw {code: 409} })
  f.batch.mock.mockImplementation(async () => ({code: 409, result: [{statusCode: 409}, {statusCode: 424}]}))
  const response = await submit(f.request('{"kind":"route-errors","stars":5,"findings":0}'), f.context,
    () => f.storage, () => Buffer.alloc(32, 1), () => new Date('2026-10-11T12:00:00Z'))
  assert.equal(response.status, 409)
  const [operations, kind] = f.batch.mock.calls[0].arguments
  assert.equal(kind, 'route-errors')
  assert.equal(operations.length, 2)
  assert.equal(operations[0].operationType, 'Create')
  assert.equal(operations[1].operationType, 'Patch')
  assert.equal(JSON.stringify(operations).includes('198.51.100.1'), false)
  assert.equal(JSON.stringify(operations).includes('172800'), true)
  assert.match(JSON.stringify(operations), /evaluatedVotes/)
  assert.match(JSON.stringify(operations), /reportedFindings/)
})

test('evaluated zero contributes an evaluated vote, unavailable does not', async () => {
  for (const findings of [0, null]) {
    const f = fixture()
    const response = await submit(f.request(JSON.stringify({kind: 'route-errors', stars: 3, findings})),
      f.context, () => f.storage, () => Buffer.alloc(32))
    assert.equal(response.status, 200)
    const operations = JSON.stringify(f.batch.mock.calls[0].arguments)
    assert.equal(operations.includes('evaluatedVotes'), findings !== null)
    assert.equal(operations.includes('reportedFindings'), findings !== null)
  }
})

test('batch and read failures surface unavailable instead of a successful empty vote', async () => {
  const f = fixture()
  f.batch.mock.mockImplementation(async () => ({code: 424, result: [{statusCode: 424}, {statusCode: 429}]}))
  const response = await submit(f.request('{"kind":"route-errors","stars":5,"findings":null}'),
    f.context, () => f.storage, () => Buffer.alloc(32))
  assert.equal(response.status, 503)
  assert.equal(f.errors.mock.callCount(), 1)
  mock.method(f.item, 'read', async () => { throw {code: 503} })
  assert.equal((await read(new HttpRequest({method: 'GET', url: 'https://fixture/api/aggregate?kind=route-errors'}),
    f.context, () => f.storage)).status, 503)
})
