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
