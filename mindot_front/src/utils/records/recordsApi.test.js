import test from 'node:test'
import assert from 'node:assert/strict'
import { createRecordsApi } from './recordsApi.js'

test('AI suggestion rejection posts to the record reject endpoint without replacing the raw record', async () => {
  const calls = []
  const response = {
    emotionRecordId: 7,
    rawText: '원문 유지',
    completionStatus: 'QUICK',
    analysisStatus: 'REJECTED',
  }
  const api = createRecordsApi({
    post: async (...args) => {
      calls.push(args)
      return { data: response }
    },
  })

  const result = await api.rejectEmotionRecordAnalysis(7)

  assert.deepEqual(calls, [['/api/records/7/reject']])
  assert.equal(result, response)
})

test('missing information questions are loaded from the record review endpoint', async () => {
  const calls = []
  const response = {
    emotionRecordId: 7,
    completionStatus: 'PARTIAL',
    questions: [{ fieldName: 'primaryIntensity', question: '강도는 어느 정도였나요?', required: false }],
  }
  const api = createRecordsApi({
    get: async (...args) => {
      calls.push(args)
      return { data: response }
    },
  })

  const result = await api.getMissingInformationQuestions(7)

  assert.deepEqual(calls, [['/api/records/7/questions/missing']])
  assert.equal(result, response)
})
