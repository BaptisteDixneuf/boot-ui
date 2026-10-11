<script setup>
import {nextTick, onBeforeUnmount, ref, useId, watch} from 'vue'
import {getJson} from '../../api.js'
import {formatLoadError} from '../../utils/loadError.js'

const props = defineProps({
  check: {type: Object, required: true},
  readOnly: {type: Boolean, default: false}
})
const aggregate = ref(null)
const error = ref('')
const stars = ref(0)
const consent = ref(false)
const busy = ref(false)
const rated = ref(false)
const opened = ref(false)
const viewButton = ref(null)
const controlId = useId()
let pending = null
let generation = 0
function resetRequest() {
  generation++
  pending?.abort()
  pending = null
  busy.value = false
}
watch(
  () => props.check.kind,
  () => {
    resetRequest()
    opened.value = false
    aggregate.value = null
    rated.value = false
    consent.value = false
    error.value = ''
    stars.value = 0
  }
)
onBeforeUnmount(resetRequest)

function feedbackError(e, fallback) {
  return formatLoadError(typeof e?.body?.error === 'string' ? e.body.error : e, fallback)
}

async function viewRatings() {
  if (busy.value) return
  const current = generation
  pending = new AbortController()
  busy.value = true
  error.value = ''
  try {
    const response = await getJson(
      `api/runtime-insights/feedback/aggregate?kind=${encodeURIComponent(props.check.kind)}`,
      {signal: pending.signal}
    )
    if (current !== generation) return
    aggregate.value = response
    opened.value = true
  } catch (e) {
    if (current === generation) error.value = feedbackError(e, 'Community ratings are unavailable.')
  } finally {
    if (current === generation) busy.value = false
  }
}

async function vote() {
  if (busy.value || rated.value || !consent.value || props.readOnly || stars.value < 1 || stars.value > 5) return
  const current = generation
  pending = new AbortController()
  busy.value = true
  error.value = ''
  try {
    const response = await getJson('api/runtime-insights/feedback/vote', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      signal: pending.signal,
      body: JSON.stringify({
        kind: props.check.kind,
        stars: stars.value
      })
    })
    if (current !== generation) return
    aggregate.value = response
    rated.value = true
  } catch (e) {
    if (current === generation) error.value = feedbackError(e, 'Could not submit your rating.')
  } finally {
    if (current === generation) {
      busy.value = false
      if (rated.value) {
        await nextTick()
        viewButton.value?.focus()
      }
    }
  }
}
</script>

<template>
  <div class="insight-feedback border rounded p-3 mb-2">
    <h3 class="h6 mb-1">{{ check.title }}</h3>
    <p class="small text-muted mb-2">
      {{
        check.status === 'EVALUATED'
          ? `${check.findings} findings in this run`
          : `Check ${check.status.toLowerCase().replaceAll('_', ' ')}; findings unavailable`
      }}. Community ratings are self-reported, not a correctness assessment.
    </p>
    <p class="small text-muted mb-2">
      Viewing or submitting contacts the configured Azure service through this local BootUI API. The service sees this
      server's public egress IP (or its proxy's IP); BootUI sends the check kind, and on voting, your stars and
      {{ check.status === 'EVALUATED' ? 'finding count (including zero)' : 'no finding count' }}. No routes, evidence,
      or raw findings are sent.
    </p>
    <button
      ref="viewButton"
      type="button"
      class="btn btn-sm btn-outline-secondary"
      :disabled="busy"
      @click="viewRatings"
    >
      {{ opened ? 'Refresh community ratings' : 'View community ratings' }}
    </button>
    <p role="status" class="small mt-2 mb-0">
      {{
        busy
          ? 'Contacting the community service…'
          : rated
            ? 'Thank you. Your rating was recorded.'
            : opened
              ? 'Community ratings loaded.'
              : ''
      }}
    </p>
    <p v-if="error" role="alert" class="text-danger small mt-2 mb-0">{{ error }}</p>
    <div v-if="opened && aggregate" class="mt-2">
      <p class="small mb-1">
        {{ aggregate.votes }} ratings · average {{ aggregate.votes ? aggregate.average.toFixed(1) : '—' }}/5 ·
        {{ aggregate.reportedFindings }} self-reported findings from {{ aggregate.evaluatedVotes }} evaluated votes
      </p>
      <ul class="list-inline small mb-2" aria-label="Star distribution">
        <li v-for="(count, index) in aggregate.stars" :key="index" class="list-inline-item">
          {{ Number(index) + 1 }} star: {{ count }}
        </li>
      </ul>
      <template v-if="!rated && !readOnly">
        <fieldset class="mb-2">
          <legend class="fs-6 mb-1">Rate this check</legend>
          <label v-for="number in 5" :key="number" class="feedback-choice me-2 small">
            <input
              v-model.number="stars"
              class="form-check-input"
              type="radio"
              :name="controlId"
              :value="number"
              :disabled="busy"
            />
            {{ number }} {{ number === 1 ? 'star' : 'stars' }}
          </label>
        </fieldset>
        <label class="feedback-choice small mb-2">
          <input v-model="consent" class="form-check-input" type="checkbox" :disabled="busy" />
          I agree to send this rating and the disclosed data to the community service.
        </label>
        <button type="button" class="btn btn-sm btn-primary" :disabled="busy || !consent || !stars" @click="vote">
          Submit rating
        </button>
      </template>
      <p v-else-if="readOnly && !rated" class="small text-muted mb-0">
        This panel is read-only; ratings cannot be submitted.
      </p>
    </div>
  </div>
</template>

<style scoped>
.feedback-choice {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  min-height: 44px;
}
.feedback-choice .form-check-input {
  flex-shrink: 0;
  margin-top: 0;
}
</style>
