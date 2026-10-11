import assert from 'node:assert/strict'
import {test} from 'node:test'
import {clientIp, emptyAggregate, parseVote, publicAggregate, voteId} from './feedback.js'

test('evaluated zero is distinct from unavailable, and rejects extra fields', () => {
  assert.deepEqual(parseVote({kind: 'route-errors', stars: 5, findings: 0}),
    {kind: 'route-errors', stars: 5, findings: 0})
  assert.deepEqual(parseVote({kind: 'route-errors', stars: 1, findings: null}),
    {kind: 'route-errors', stars: 1, findings: null})
  for (const vote of [
    {kind: '../internal', stars: 3, findings: 0},
    {kind: 'valid', stars: 0, findings: null},
    {kind: 'valid', stars: 6, findings: null},
    {kind: 'valid', stars: 3, findings: -1},
    {kind: 'valid', stars: 3, findings: 0, ip: '127.0.0.1'}
  ]) assert.equal(parseVote(vote), null)
})

test('only a syntactically valid platform header is accepted', () => {
  assert.equal(clientIp(new Headers({'x-arr-clientip': '2001:db8::1'})), '2001:db8::1')
  assert.equal(clientIp(new Headers({'x-forwarded-for': '198.51.100.3'})), null)
  assert.equal(clientIp(new Headers({'x-arr-clientip': '198.51.100.1, 10.0.0.1'})), null)
})

test('keyed daily identifiers do not expose an address and vary across kinds/days', () => {
  const key = Buffer.alloc(32, 1)
  const vote = {kind: 'route-errors', stars: 5, findings: 0}
  const id = voteId(key, '2026-10-11', vote, '198.51.100.5')
  assert.equal(id, voteId(key, '2026-10-11', {...vote, stars: 2}, '198.51.100.5'))
  assert.notEqual(id, voteId(key, '2026-10-12', vote, '198.51.100.5'))
  assert.notEqual(id, voteId(key, '2026-10-11', {...vote, kind: 'sql-work'}, '198.51.100.5'))
  assert.equal(id.includes('198.51'), false)
})

test('community aggregate includes zeros and weighted star average', () => {
  const a = {...emptyAggregate('route-errors'), votes: 3, star1: 1, star5: 2, evaluatedVotes: 2, reportedFindings: 0}
  assert.deepEqual(publicAggregate(a), {
    kind: 'route-errors', votes: 3, stars: [1, 0, 0, 0, 2],
    average: 11 / 3, evaluatedVotes: 2, reportedFindings: 0
  })
})
