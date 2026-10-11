import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, expect, it, vi} from 'vitest'
import InsightFeedback from './InsightFeedback.vue'

const getJson = vi.hoisted(() => vi.fn())
vi.mock('../../api.js', () => ({getJson}))
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
  expect(getJson).toHaveBeenCalledWith('api/runtime-insights/feedback/aggregate?kind=repeated-selects')
  expect(wrapper.text()).toContain('average 4.5/5')
  expect(wrapper.get('button.btn-primary').attributes('disabled')).toBeDefined()
  await wrapper.findAll('input[type="radio"]')[4].setValue()
  await wrapper.get('input[type="checkbox"]').setValue(true)
  await wrapper.get('button.btn-primary').trigger('click')
  await flushPromises()
  expect(JSON.parse(getJson.mock.calls[1][1].body)).toEqual({kind: 'repeated-selects', stars: 5})
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
