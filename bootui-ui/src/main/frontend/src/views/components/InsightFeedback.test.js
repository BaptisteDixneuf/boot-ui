import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, expect, it, vi} from 'vitest'
import InsightFeedback from './InsightFeedback.vue'
import {ApiError} from '../../api.js'

const getJson = vi.hoisted(() => vi.fn())
vi.mock('../../api.js', async (original) => ({...(await original()), getJson}))
const aggregate = {
  kind: 'repeated-selects',
  votes: 2,
  stars: [0, 0, 0, 1, 1],
  average: 4.5,
  evaluatedVotes: 1,
  reportedFindings: 0
}
const check = {kind: 'repeated-selects', title: 'Repeated SELECTs', status: 'EVALUATED', findings: 0}

afterEach(() => getJson.mockReset())

it('contacts nobody on mount and sends evaluated zero only after click and consent', async () => {
  getJson.mockResolvedValue(aggregate)
  const wrapper = mount(InsightFeedback, {props: {check}})
  expect(getJson).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('0 findings in this run')
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(getJson).toHaveBeenCalledWith('api/runtime-insights/feedback/aggregate?kind=repeated-selects', {
    signal: expect.any(AbortSignal)
  })
  expect(wrapper.text()).toContain('average 4.5/5')
  expect(wrapper.get('button.btn-primary').attributes('disabled')).toBeDefined()
  await wrapper.findAll('input[type="radio"]')[4].setValue()
  await wrapper.get('input[type="checkbox"]').setValue(true)
  await wrapper.get('button.btn-primary').trigger('click')
  await flushPromises()
  expect(JSON.parse(getJson.mock.calls[1][1].body)).toEqual({kind: 'repeated-selects', stars: 5})
  wrapper.unmount()
})

it.each([409, 503])('shows the server explanation for HTTP %s and preserves local findings', async (status) => {
  const message =
    status === 409 ? 'Already rated this check today from this network.' : 'Community feedback is not configured.'
  getJson.mockRejectedValue(new ApiError(status, {error: message}))
  const wrapper = mount(InsightFeedback, {props: {check}})
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain(message)
  expect(wrapper.text()).toContain('0 findings in this run')
  wrapper.unmount()
})

it('ignores stale responses after a check changes and resets the selected rating', async () => {
  let resolve
  getJson.mockReturnValue(
    new Promise((done) => {
      resolve = done
    })
  )
  const wrapper = mount(InsightFeedback, {props: {check}})
  await wrapper.get('button').trigger('click')
  const signal = getJson.mock.calls[0][1].signal
  await wrapper.setProps({check: {...check, kind: 'route-errors'}})
  expect(signal.aborted).toBe(true)
  resolve(aggregate)
  await flushPromises()
  expect(wrapper.text()).not.toContain('average')
  expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
  wrapper.unmount()
})

it('keeps radio groups independent even when two instances show the same check', async () => {
  getJson.mockResolvedValue(aggregate)
  const wrapper = mount({
    components: {InsightFeedback},
    setup: () => ({check}),
    template: '<InsightFeedback :check="check" /><InsightFeedback :check="check" />'
  })
  const [first, second] = wrapper.findAllComponents(InsightFeedback)
  await first.get('button').trigger('click')
  await second.get('button').trigger('click')
  await flushPromises()
  expect(first.get('input[type="radio"]').attributes('name')).not.toEqual(
    second.get('input[type="radio"]').attributes('name')
  )
  expect(first.findAll('[role="status"]')).toHaveLength(1)
  wrapper.unmount()
})

it('keeps local finding information when the community is unavailable', async () => {
  getJson.mockRejectedValue(new Error('offline'))
  const wrapper = mount(InsightFeedback, {props: {check: {...check, status: 'UNAVAILABLE'}, readOnly: true}})
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('findings unavailable')
  expect(wrapper.find('[role="alert"]').exists()).toBe(true)
  expect(getJson).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})
