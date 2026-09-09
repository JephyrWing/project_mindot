import test from 'node:test'
import assert from 'node:assert/strict'
import { acceptSessionView } from './sessionView.js'

test('old attempt poll cannot replace retry at the same input revision', () => {
  const current = { sessionId: 1, revision: 2, job: { jobId: 4, status: 'PROCESSING' } }
  assert.equal(acceptSessionView(current, { ...current, job: { jobId: 3, status: 'FAILED' } }), false)
  assert.equal(acceptSessionView(current, { ...current, job: { jobId: 4, status: 'FAILED' } }), true)
})
test('saved response or cancel outranks every old response', () => {
  const completed = { sessionId: 1, revision: 4, status: 'CANCELLED' }
  assert.equal(acceptSessionView(completed, { sessionId: 1, revision: 3, status: 'OPEN' }), false)
})
