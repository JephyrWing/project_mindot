import { expect, test } from '@playwright/test'
import { completeRecord, mockApi, openReflection, readJsonBody, useAuthenticatedSession, weeklyReport, monthlyReport } from './support'

test.beforeEach(async ({ page }) => useAuthenticatedSession(page))

for (const inputType of ['TEXT', 'VOICE_STT']) {
  test(`${inputType}: one save opens the shared editor after polling the same record`, async ({ page }) => {
    let creates = 0, reads = 0, reanalyzes = 0, patterns = 0
    const partial = completeRecord({ completionStatus: 'PARTIAL', automaticThought: null })
    if (inputType === 'VOICE_STT') await page.addInitScript(() => {
      const track = { stop() {} }
      Object.defineProperty(navigator.mediaDevices, 'getUserMedia', { value: async () => ({ getTracks: () => [track] }) })
      class Recorder {
        static isTypeSupported() { return true }
        mimeType = 'audio/webm'; state = 'inactive'; ondataavailable: any; onstop: any
        start() { this.state = 'recording' }
        stop() { this.state = 'inactive'; this.ondataavailable?.({ data: new Blob(['test'], { type: this.mimeType }) }); this.onstop?.() }
      }
      window.MediaRecorder = Recorder as any
    })
    await mockApi(page, (request, url) => {
      if (url.pathname.includes('/stt')) return { body: { transcript: '음성으로 남긴 마음' } }
      if (url.pathname === '/api/records/quick') {
        creates++
        expect(readJsonBody(request).inputType).toBe(inputType)
        expect(request.headers()['idempotency-key']).toBeTruthy()
        return { body: { ...partial, completionStatus: 'QUICK', analysisStatus: 'PROCESSING' } }
      }
      if (url.pathname === '/api/records/1') {
        reads++
        return { body: reads <= 2 ? { ...partial, completionStatus: 'QUICK', analysisStatus: 'PROCESSING' } : partial }
      }
      if (url.pathname.endsWith('/reanalyze')) { reanalyzes++; return { body: partial } }
      if (url.pathname.endsWith('/confirm')) return { body: completeRecord({ automaticThought: null }) }
      if (url.pathname.endsWith('/pattern-explanation')) {
        patterns++
        return { body: { similarCaseCount: 3, patternSummary: '유사한 과거 CBT에서 확인한 흐름이에요.' } }
      }
    })
    await page.goto('/records/new')
    if (inputType === 'VOICE_STT') {
      await page.getByRole('button', { name: '음성 녹음', exact: true }).click()
      await page.getByRole('button', { name: '녹음 중지', exact: true }).click()
      await expect(page.getByLabel('지금의 감정')).toHaveValue('음성으로 남긴 마음')
    } else await page.getByLabel('지금의 감정').fill('오늘의 마음')
    await page.getByRole('button', { name: '기록하기', exact: true }).dblclick()
    await expect(page).toHaveURL('/records/1')
    await expect(page.getByText(/AI 분석 중이며 완료 상태/)).toBeVisible()
    await expect(page.getByRole('button', { name: '다시 분석하기', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '수정한 결과 확정하기' })).toBeVisible({ timeout: 12000 })
    await expect(page.locator('textarea[name="situationText"]')).toBeFocused()
    expect(creates).toBe(1); expect(reanalyzes).toBe(0)
    expect(patterns).toBe(0)
    await page.getByRole('button', { name: '수정한 결과 확정하기' }).click()
    await expect(page.getByText('유사한 과거 CBT에서 확인한 흐름이에요.')).toBeVisible()
    expect(patterns).toBe(1)
  })
}

test('failed analysis and failed detail lookup recover without another create', async ({ page }) => {
  let creates = 0, failRead = true, retries = 0
  const saved = completeRecord({ completionStatus: 'QUICK', analysisStatus: 'FAILED' })
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/records/quick') { creates++; return { body: saved } }
    if (url.pathname === '/api/records/1') return failRead ? { status: 503 } : { body: saved }
    if (url.pathname.endsWith('/reanalyze')) { retries++; return { body: { ...saved, completionStatus: 'PARTIAL', analysisStatus: 'COMPLETED' } } }
  })
  await page.goto('/records/new')
  await page.getByLabel('지금의 감정').fill('보존할 원문')
  await page.getByRole('button', { name: '기록하기', exact: true }).click()
  await expect(page.getByText('기존 기록을 다시 조회합니다. 원문을 새로 저장할 필요가 없습니다.')).toBeVisible()
  failRead = false
  await page.getByRole('button', { name: '다시 불러오기' }).click()
  await expect(page.getByText('원문은 저장되었습니다. AI 분석에 실패했습니다. 다시 분석할 수 있습니다.')).toBeVisible()
  await page.getByRole('button', { name: '다시 분석하기', exact: true }).click()
  await expect(page.getByRole('button', { name: '수정한 결과 확정하기' })).toBeVisible()
  expect(creates).toBe(1); expect(retries).toBe(1)
})

test('optional thought confirmation, custom emotion roundtrip, CBT input and failed OPEN retry', async ({ page }) => {
  let record = completeRecord({ completionStatus: 'PARTIAL', automaticThought: null })
  let saves = 0, opens = 0
  const keys: string[] = []
  await mockApi(page, (request, url) => {
    if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: record }
    if (url.pathname.endsWith('/confirm')) {
      const body = readJsonBody(request)
      expect(body.automaticThought).toBeNull(); expect(body.primaryEmotionCode).toBe('먹먹함')
      expect(body.customEmotion).toBeUndefined()
      record = { ...record, ...body, completionStatus: 'COMPLETE' }; return { body: record }
    }
    if (url.pathname === '/api/records/1' && request.method() === 'PATCH') {
      saves++; expect(readJsonBody(request)).toEqual({ automaticThought: '혼자 남겨질 것 같았다' })
      record = { ...record, automaticThought: readJsonBody(request).automaticThought }; return { body: record }
    }
    if (url.pathname === '/api/reflections/open' && request.method() === 'POST') {
      opens++; keys.push(request.headers()['idempotency-key'])
      return opens === 1 ? 'abort' : { body: openReflection() }
    }
  })
  await page.goto('/records/1')
  await page.getByLabel('자동으로 떠오른 생각 (선택)').fill('   ')
  await page.locator('select[name="primaryEmotionCode"]').selectOption('__CUSTOM_EMOTION__')
  const custom = page.getByRole('textbox', { name: '직접 입력 감정 이름' })
  await expect(custom).toBeVisible()
  await custom.fill('   ')
  await page.getByRole('button', { name: '수정한 결과 확정하기' }).click()
  await expect(page.getByRole('alert')).toContainText('대표 감정 이름을 입력해 주세요.')
  await custom.fill('  먹먹함  ')
  await page.getByRole('button', { name: '수정한 결과 확정하기' }).click()
  await expect(page.getByText('사용자 확정값')).toBeVisible()
  await page.reload()
  await expect(page.locator('.emotion-detail-tags')).toContainText('먹먹함')
  await page.getByRole('button', { name: 'CBT 검사 하기' }).click()
  await page.getByRole('button', { name: '성찰 시작하기' }).click()
  await page.getByLabel('그때 처음 떠오른 생각').fill('혼자 남겨질 것 같았다')
  await page.getByRole('button', { name: '저장하고 시작하기' }).dblclick()
  await expect(page.getByText('생각은 저장됐습니다. 성찰 시작을 다시 시도할 수 있어요.')).toBeVisible()
  await page.getByRole('button', { name: '성찰 시작하기' }).click()
  await expect(page).toHaveURL('/cbt/sessions/51')
  expect(saves).toBe(1); expect(opens).toBe(2); expect(keys[0]).toBe(keys[1])
})

for (const otherChoice of [false, true]) {
  test(`custom label exact mapping and hidden field exclusion: switch=${otherChoice}`, async ({ page }) => {
    let submitted: any
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1') return { body: completeRecord({ completionStatus: 'PARTIAL', primaryEmotionCode: '먹먹함' }) }
      if (url.pathname.endsWith('/confirm')) { submitted = readJsonBody(request); return { body: completeRecord(submitted) } }
    })
    await page.goto('/records/1')
    await expect(page.locator('select[name="primaryEmotionCode"]')).toHaveValue('__CUSTOM_EMOTION__')
    await expect(page.getByRole('textbox', { name: '직접 입력 감정 이름' })).toHaveValue('먹먹함')
    await page.getByRole('textbox', { name: '직접 입력 감정 이름' }).fill('불안')
    if (otherChoice) {
      await page.locator('select[name="primaryEmotionCode"]').selectOption('JOY')
      await expect(page.getByRole('textbox', { name: '직접 입력 감정 이름' })).toHaveCount(0)
    }
    await page.getByRole('button', { name: '수정한 결과 확정하기' }).click()
    await expect(page.getByText('사용자 확정값')).toBeVisible()
    expect(submitted.primaryEmotionCode).toBe(otherChoice ? 'JOY' : 'ANXIETY')
    expect(submitted.customEmotion).toBeUndefined()
  })
}

test('legacy OTHER stays a legacy choice', async ({ page }) => {
  await mockApi(page, (_request, url) => url.pathname === '/api/records/1'
    ? { body: completeRecord({ completionStatus: 'PARTIAL', primaryEmotionCode: 'OTHER' }) } : undefined)
  await page.goto('/records/1')
  await expect(page.locator('.emotion-detail-tags')).toContainText('기타')
  await expect(page.locator('select[name="primaryEmotionCode"]')).toHaveValue('OTHER')
  await expect(page.getByRole('textbox', { name: '직접 입력 감정 이름' })).toHaveCount(0)
})

test('asynchronous safety notice precedes editor focus and is tracked once', async ({ page }) => {
  let reads = 0, notices = 0
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/records/quick') return { body: completeRecord({ analysisStatus: 'PROCESSING' }) }
    if (url.pathname === '/api/records/1') {
      reads++
      return { body: reads <= 2 ? completeRecord({ completionStatus: 'QUICK', analysisStatus: 'PROCESSING' })
        : completeRecord({ completionStatus: 'PARTIAL', safetyNotice: { safetyEventId: 123, actionCode: 'SHOW_CRISIS_NOTICE', riskLevel: 'CRISIS' } }) }
    }
    if (url.pathname.includes('/notice-shown')) { notices++; return { status: 204 } }
  })
  await page.goto('/records/new')
  await page.getByLabel('지금의 감정').fill('안전 안내 테스트 원문')
  await page.getByRole('button', { name: '기록하기', exact: true }).click()
  await expect(page.getByRole('alertdialog')).toBeVisible({ timeout: 12000 })
  await expect(page.getByRole('button', { name: '안전 안내 확인' })).toBeFocused()
  await page.getByRole('button', { name: '안전 안내 확인' }).click()
  await expect(page.locator('textarea[name="situationText"]')).toBeFocused()
  expect(notices).toBe(1)
})

for (const path of ['/records', '/daily-care', '/reports/weekly', '/reports/monthly']) {
  test(`custom names remain visible on ${path}`, async ({ page }) => {
    const custom = '먹먹함'
    await mockApi(page, (_request, url) => {
      if (url.pathname === '/api/records' || url.pathname === '/api/records/semantic-search') return { body: {
        content: [completeRecord({ primaryEmotionCode: custom })], totalElements: 1, totalPages: 1, page: 0, size: 5,
      } }
      if (url.pathname === '/api/reports/weekly') return { body: weeklyReport({ dominantEmotionCode: custom, emotionCounts: { [custom]: 2, ANXIETY: 1 } }) }
      if (url.pathname === '/api/reports/monthly') return { body: monthlyReport('2026-09', { dominantEmotionCode: custom, emotionCounts: { [custom]: 2, ANXIETY: 1 } }) }
    })
    await page.goto(path === '/reports/weekly' ? `${path}?weekStart=2026-09-14` : path)
    await expect(page.getByText(custom, { exact: true }).first()).toBeVisible()
  })
}

test('poll lookup failure pauses reads; retrying reads the same record', async ({ page }) => {
  let reanalyzes = 0, fail = false, complete = false
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/records/1') {
      if (fail) return { status: 503 }
      return { body: completeRecord({ completionStatus: complete ? 'PARTIAL' : 'QUICK', analysisStatus: complete ? 'COMPLETED' : 'PROCESSING' }) }
    }
    if (url.pathname.endsWith('/reanalyze')) { reanalyzes++; return { status: 500 } }
  })
  await page.goto('/records/1')
  await expect(page.getByText(/AI 분석 중이며 완료 상태/)).toBeVisible()
  fail = true
  await expect(page.getByRole('heading', { name: '상세 정보를 불러오지 못했습니다.' })).toBeVisible({ timeout: 10000 })
  fail = false; complete = true
  await page.getByRole('button', { name: '다시 불러오기' }).click()
  await expect(page.getByRole('button', { name: '수정한 결과 확정하기' })).toBeVisible()
  expect(reanalyzes).toBe(0)
})

test('reanalysis PROCESSING response is a status, followed by GET only', async ({ page }) => {
  let processing = false, calls = 0
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/records/1') return { body: completeRecord({ completionStatus: processing ? 'PARTIAL' : 'QUICK', analysisStatus: processing ? 'COMPLETED' : 'FAILED' }) }
    if (url.pathname.endsWith('/reanalyze')) {
      processing = true; calls++
      return { body: completeRecord({ completionStatus: 'QUICK', analysisStatus: 'PROCESSING' }) }
    }
  })
  await page.goto('/records/1')
  await page.getByRole('button', { name: '다시 분석하기', exact: true }).click()
  await expect(page.getByText(/AI 분석 중이며 완료 상태/)).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '수정한 결과 확정하기' })).toBeVisible({ timeout: 10000 })
  expect(calls).toBe(1)
})

test('semantic search includes custom names with the existing all-emotions filter', async ({ page }) => {
  let searched = false
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/records/semantic-search') {
      searched = true
      expect(url.searchParams.get('emotionCode')).toBeNull()
      return { body: { content: [completeRecord({ primaryEmotionCode: '먹먹함' })], totalElements: 1, totalPages: 1, page: 0, size: 3 } }
    }
  })
  await page.goto('/records')
  await page.getByRole('button', { name: '의미 검색' }).click()
  await page.getByLabel('기록 검색').fill('나의 기록')
  await page.getByRole('button', { name: '검색', exact: true }).click()
  await expect(page.getByText('먹먹함', { exact: true })).toBeVisible()
  expect(searched).toBe(true)
})

test('monthly summary preserves a custom name containing a known code', async ({ page }) => {
  const name = 'JOY 뒤의 허전함'
  await mockApi(page, (_request, url) => url.pathname === '/api/reports/monthly'
    ? { body: monthlyReport('2026-09', { emotionCounts: { [name]: 2 }, dominantEmotionCode: name, summaryText: `${name} 감정과 WORK 상황이 자주 기록되었습니다.` }) } : undefined)
  await page.goto('/reports/monthly')
  await expect(page.locator('.monthly-report-summary-text')).toContainText(`${name} 감정과 업무 상황`)
})

for (const completionStatus of ['COMPLETE', 'PARTIAL']) {
  test(`${completionStatus}: an existing thought opens CBT without another thought write`, async ({ page }) => {
    let mutations = 0
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: completeRecord({ completionStatus }) }
      if (url.pathname.endsWith('/confirm') || (url.pathname === '/api/records/1' && request.method() === 'PATCH')) { mutations++; return { status: 409 } }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') return { body: openReflection() }
    })
    await page.goto('/cbt?emotionRecordId=1')
    await page.getByRole('button', { name: '성찰 시작하기' }).click()
    await expect(page).toHaveURL('/cbt/sessions/51')
    await expect(page.getByText('그 생각을 뒷받침하는 근거가 있나요?')).toBeVisible()
    expect(mutations).toBe(0)
  })
}
