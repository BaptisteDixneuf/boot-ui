// @ts-check

export function registerInsightFeedbackTests(test, expect) {
  test('community feedback stays explicit, consent-gated, and keyboard accessible', async ({page}) => {
    let reads = 0
    let votes = 0
    await page.route('**/api/runtime-insights/feedback/aggregate?*', (route) => {
      reads++
      const kind = new URL(route.request().url()).searchParams.get('kind')
      return route.fulfill({
        json: {
          kind,
          votes: 2,
          stars: [0, 0, 0, 1, 1],
          average: 4.5,
          evaluatedVotes: 1,
          reportedFindings: 0
        }
      })
    })
    await page.route('**/api/runtime-insights/feedback/vote', (route) => {
      votes++
      expect(route.request().postDataJSON()).toEqual({kind: 'repeated-selects', stars: 5})
      return route.fulfill({
        json: {
          kind: 'repeated-selects',
          votes: 3,
          stars: [0, 0, 0, 1, 2],
          average: 14 / 3,
          evaluatedVotes: 2,
          reportedFindings: 0
        }
      })
    })
    await page.goto('/bootui/#/runtime-insights')
    await page.getByRole('tab', {name: /^Coverage & limits/}).click()
    const section = page.locator('section[aria-labelledby="insight-feedback-title"]')
    await section.locator('summary').focus()
    await page.keyboard.press('Enter')
    const check = section
      .locator('.insight-feedback')
      .filter({has: page.getByRole('heading', {name: 'Repeated SELECTs', exact: true})})
    await expect(check).toBeVisible()
    expect(reads).toBe(0)
    expect(votes).toBe(0)
    const view = check.getByRole('button', {name: 'View community ratings', exact: true})
    await view.focus()
    await page.keyboard.press('Enter')
    await expect(check).toContainText('average 4.5/5')
    const submit = check.getByRole('button', {name: 'Submit rating'})
    await expect(submit).toBeDisabled()
    await check.getByRole('radio', {name: '5 stars', exact: true}).focus()
    await page.keyboard.press('Space')
    await expect(submit).toBeDisabled()
    await check.getByRole('checkbox').focus()
    await page.keyboard.press('Space')
    await expect(submit).toBeEnabled()
    expect(votes).toBe(0)
    await submit.focus()
    await page.keyboard.press('Enter')
    await expect(check.getByRole('status')).toHaveText('Thank you. Your rating was recorded.')
    await expect(check.getByRole('status')).toHaveCount(1)
    await expect(check.getByRole('button', {name: 'Refresh community ratings'})).toBeFocused()
    await expect(check.getByRole('radio')).toHaveCount(0)
    expect(votes).toBe(1)
    await page.setViewportSize({width: 390, height: 844})
    await expect(check).toBeVisible()
    expect(await check.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
  })

  test('unconfigured community feedback shows the native error without hiding local checks', async ({page}) => {
    await page.goto('/bootui/#/runtime-insights')
    await page.getByRole('tab', {name: /^Coverage & limits/}).click()
    const section = page.locator('section[aria-labelledby="insight-feedback-title"]')
    await section.locator('summary').click()
    const check = section.locator('.insight-feedback').first()
    await check.getByRole('button', {name: 'View community ratings'}).click()
    await expect(check.getByRole('alert')).toContainText('Community feedback is not configured.')
    await expect(check.getByRole('heading')).toBeVisible()
    await expect(page.locator('.insight-window')).toBeVisible()
  })

  test('read-only community feedback can view ratings but offers no vote controls', async ({page}) => {
    await page.route('**/api/panels', async (route) => {
      const response = await route.fetch()
      const manifest = await response.json()
      const panel = manifest.panels.find((item) => item.id === 'runtime-insights')
      panel.readOnly = true
      panel.readOnlyReason = 'Test read-only policy'
      await route.fulfill({response, json: manifest})
    })
    await page.route('**/api/runtime-insights/feedback/aggregate?*', (route) =>
      route.fulfill({
        json: {
          kind: new URL(route.request().url()).searchParams.get('kind'),
          votes: 0,
          stars: [0, 0, 0, 0, 0],
          average: 0,
          evaluatedVotes: 0,
          reportedFindings: 0
        }
      })
    )
    await page.goto('/bootui/#/runtime-insights')
    await page.getByRole('tab', {name: /^Coverage & limits/}).click()
    const section = page.locator('section[aria-labelledby="insight-feedback-title"]')
    await section.locator('summary').click()
    const check = section.locator('.insight-feedback').first()
    await check.getByRole('button', {name: 'View community ratings'}).click()
    await expect(check).toContainText('This panel is read-only; ratings cannot be submitted.')
    await expect(check.getByRole('radio')).toHaveCount(0)
    await expect(check.getByRole('checkbox')).toHaveCount(0)
    await expect(check.getByRole('button', {name: 'Submit rating'})).toHaveCount(0)
  })
}
