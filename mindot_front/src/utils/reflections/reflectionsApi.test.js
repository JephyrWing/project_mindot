// Prepared only; execute after ChatGPT review of the exact commit.
import test from 'node:test'
import assert from 'node:assert/strict'
import { createReflectionsApi } from './reflectionsApi.js'

test('ambiguous transport replay preserves body, key and revision', async () => {
  const calls = []
  const api = createReflectionsApi({ post: async (...args) => {
    calls.push(args)
    if (calls.length === 1) throw new Error('response lost')
    return { data: { sessionId: 1, revision: 4 } }
  } })
  await assert.rejects(api.submitReflectionAnswer(1, '  원문 유지  ', 'same-key', 2))
  await api.submitReflectionAnswer(1, '  원문 유지  ', 'same-key', 2)
  assert.deepEqual(calls[0], calls[1])
  assert.deepEqual(calls[1][1], { answer: '  원문 유지  ' })
  assert.equal(calls[1][2].headers['If-Match'], '2')
})

test('retry carries no replacement answer and confirm allows all rejected', async () => {
  const calls = []
  const api = createReflectionsApi({ post: async (...args) => { calls.push(args); return { data: {} } } })
  await api.retryReflection(1, 'retry-key', 2)
  assert.equal(calls[0][1], null)
  const body = { proposalId: 'active', reviews: [{ code: 'ALL_OR_NOTHING_THINKING', reviewStatus: 'REJECTED' }],
    beforeBeliefStrength: 70, afterBeliefStrength: 40, finalEmotionIntensity: 4, helpfulnessScore: 3 }
  await api.confirmReflection(1, body, 'confirm-key', 9)
  assert.deepEqual(calls[1][1], body)
  assert.ok(!('afterText' in calls[1][1]))
  assert.equal(calls[1][2].headers['If-Match'], '9')
})
