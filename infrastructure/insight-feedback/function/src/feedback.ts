import {createHmac} from 'node:crypto'
import {isIP as validIP} from 'node:net'

export type Vote = {kind: string; stars: number; findings: number | null}
export type Aggregate = {
  id: 'aggregate'
  kind: string
  votes: number
  star1: number
  star2: number
  star3: number
  star4: number
  star5: number
  evaluatedVotes: number
  reportedFindings: number
}

/** Linux App Service overwrites client-ip at its front end. Never fall back to client-supplied headers. */
export function clientIp(headers: {get(name: string): string | null}): string | null {
  const value = headers.get('client-ip')
  // No fallback to untrusted browser-supplied XFF or TCP peer.
  if (!value || value !== value.trim() || validIP(value) === 0) return null
  return validIP(value) === 6 ? new URL(`http://[${value}]/`).hostname.slice(1, -1) : value
}

export function parseVote(value: unknown): Vote | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const fields = Object.keys(value)
  if (fields.length !== 3 || !fields.every((field) => ['kind', 'stars', 'findings'].includes(field))) return null
  const vote = value as Record<string, unknown>
  if (
    typeof vote.kind !== 'string' ||
    !/^[a-z0-9-]{1,64}$/.test(vote.kind) ||
    !Number.isInteger(vote.stars) ||
    (vote.stars as number) < 1 ||
    (vote.stars as number) > 5 ||
    (vote.findings !== null &&
      (!Number.isInteger(vote.findings) || (vote.findings as number) < 0 || (vote.findings as number) > 1000))
  ) return null
  return vote as Vote
}

export function voteId(secret: Buffer, day: string, vote: Vote, ip: string): string {
  return createHmac('sha256', secret).update(`${day}\0${vote.kind}\0${ip}`).digest('hex')
}

export function publicAggregate(value: Aggregate) {
  const stars = [value.star1, value.star2, value.star3, value.star4, value.star5]
  return {
    kind: value.kind,
    votes: value.votes,
    stars,
    average: value.votes ? stars.reduce((sum, count, index) => sum + count * (index + 1), 0) / value.votes : 0,
    evaluatedVotes: value.evaluatedVotes,
    reportedFindings: value.reportedFindings
  }
}

export function emptyAggregate(kind: string): Aggregate {
  return {
    id: 'aggregate', kind, votes: 0,
    star1: 0, star2: 0, star3: 0, star4: 0, star5: 0,
    evaluatedVotes: 0, reportedFindings: 0
  }
}
