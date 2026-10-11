import {app, HttpRequest, HttpResponseInit, InvocationContext} from '@azure/functions'
import {CosmosClient, Container, type OperationInput} from '@azure/cosmos'
import {DefaultAzureCredential} from '@azure/identity'
import {clientIp, emptyAggregate, parseVote, publicAggregate, voteId, type Aggregate} from './feedback.js'

let container: Container | undefined
function store(): Container {
  if (!container) {
    const endpoint = process.env.COSMOS_ENDPOINT
    if (!endpoint || !/^https:\/\/[a-z0-9-]+\.documents\.azure\.com$/i.test(endpoint))
      throw new Error('Cosmos endpoint not configured')
    container = new CosmosClient({endpoint, aadCredentials: new DefaultAzureCredential()})
      .database('feedback').container('votes')
  }
  return container
}

function secret(): Buffer {
  const key = Buffer.from(process.env.FEEDBACK_HMAC_KEY ?? '', 'base64')
  if (key.length !== 32) throw new Error('Feedback HMAC key not configured')
  return key
}

const json = (status: number, value: object): HttpResponseInit => ({
  status, jsonBody: value, headers: {'Cache-Control': 'no-store'}
})

async function boundedBody(request: HttpRequest): Promise<string> {
  if (Number(request.headers.get('content-length')) > 256) throw new RangeError('Request too large')
  if (!request.body) return ''
  const reader = request.body.getReader()
  const chunks: Uint8Array[] = []
  let length = 0
  try {
    while (true) {
      const {value, done} = await reader.read()
      if (done) break
      const bytes = value as Uint8Array
      length += bytes.byteLength
      if (length > 256) throw new RangeError('Request too large')
      chunks.push(bytes)
    }
    return Buffer.concat(chunks).toString('utf8')
  } finally {
    await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}

async function aggregate(kind: string, storage: Container): Promise<Aggregate> {
  try {
    const {resource} = await storage.item('aggregate', kind).read<Aggregate>()
    return resource ?? emptyAggregate(kind)
  } catch (error) {
    if ((error as {code?: number}).code === 404) return emptyAggregate(kind)
    throw error
  }
}

async function read(request: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  const kind = request.query.get('kind')
  if (!kind || !/^[a-z0-9-]{1,64}$/.test(kind)) return json(400, {error: 'Invalid check kind.'})
  try {
    return json(200, publicAggregate(await aggregate(kind, store())))
  } catch (error) {
    context.error('Feedback aggregate read failed', error)
    return json(503, {error: 'Feedback temporarily unavailable.'})
  }
}

async function submit(request: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  // The platform-provided address is the BootUI server's egress/proxy IP, not a browser identifier.
  const ip = clientIp(request.headers)
  if (!ip) return json(400, {error: 'Client IP unavailable.'})
  let input: unknown
  try {
    input = JSON.parse(await boundedBody(request))
  } catch (error) {
    if (error instanceof RangeError) return json(413, {error: 'Request too large.'})
    return json(400, {error: 'Invalid JSON.'})
  }
  const vote = parseVote(input)
  if (!vote) return json(400, {error: 'Invalid feedback vote.'})
  try {
    const storage = store()
    const key = secret()
    const day = new Date().toISOString().slice(0, 10)
    // Create the aggregate once. This is not the vote: it never includes identifying data.
    try {
      await storage.items.create(emptyAggregate(vote.kind))
    } catch (error) {
      if ((error as {code?: number}).code !== 409) throw error
    }
    // One partition, one atomic batch: a duplicate create rolls back *all* counters.
    // Vote documents expire after two days; the long-lived aggregate contains counters only.
    const operations: OperationInput[] = [
      {operationType: 'Create' as const, resourceBody: {
        id: voteId(key, day, vote, ip), kind: vote.kind, ttl: 172800
      }},
      {operationType: 'Patch' as const, id: 'aggregate', resourceBody: {operations: [
        {op: 'incr' as const, path: '/votes', value: 1},
        {op: 'incr' as const, path: `/star${vote.stars}`, value: 1},
        ...(vote.findings === null ? [] : [
          {op: 'incr' as const, path: '/evaluatedVotes', value: 1},
          {op: 'incr' as const, path: '/reportedFindings', value: vote.findings}
        ])
      ]}}
    ]
    const result = await storage.items.batch(operations, vote.kind)
    if (result.code === 409 || result.result?.[0]?.statusCode === 409)
      return json(409, {error: 'Already rated this check today from this network.'})
    if ((result.code ?? 0) < 200 || (result.code ?? 0) >= 300 || result.result?.some((item) => item.statusCode >= 400))
      throw new Error('Cosmos batch failed')
    return json(200, publicAggregate(await aggregate(vote.kind, storage)))
  } catch (error) {
    context.error('Feedback vote failed', error)
    return json(503, {error: 'Feedback temporarily unavailable.'})
  }
}

app.http('insight-feedback-aggregate', {methods: ['GET'], authLevel: 'anonymous', route: 'aggregate', handler: read})
app.http('insight-feedback-vote', {methods: ['POST'], authLevel: 'anonymous', route: 'vote', handler: submit})
