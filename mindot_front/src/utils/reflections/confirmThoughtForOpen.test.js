// Prepared for a newly reviewed SHA; not executed during review fixes.
import test from 'node:test'
import assert from 'node:assert/strict'
import { confirmThoughtForOpen } from './confirmThoughtForOpen.js'

const payload = { automaticThought: '이번 일로 내 능력을 판단했다.' }
const complete = { ...payload, completionStatus: 'COMPLETE' }

test('lost confirm response is recovered from the actual COMPLETE record', async () => {
  let reads = 0
  const result = await confirmThoughtForOpen(1, payload, {
    getEmotionRecordDetail: async () => ++reads === 1 ? { completionStatus: 'PARTIAL' } : complete,
    confirmEmotionRecord: async () => { throw new Error('lost response') },
  })
  assert.equal(result, complete)
})

test('later retry after ambiguous confirm does not confirm COMPLETE again', async () => {
  let confirms = 0
  const result = await confirmThoughtForOpen(1, payload, {
    getEmotionRecordDetail: async () => complete,
    confirmEmotionRecord: async () => { confirms++; throw new Error('must not confirm twice') },
  })
  assert.equal(result, complete)
  assert.equal(confirms, 0)
})

test('different persisted thought is a conflict, not recovered success', async () => {
  await assert.rejects(confirmThoughtForOpen(1, payload, {
    getEmotionRecordDetail: async () => ({ ...complete, automaticThought: '다른 생각' }),
    confirmEmotionRecord: async () => { throw new Error('must not overwrite') },
  }), (error) => error.message === 'confirmed_thought_conflict')
})

test('409 with still PARTIAL record is not treated as success', async () => {
  const conflict = Object.assign(new Error('conflict'), { response: { status: 409 } })
  await assert.rejects(confirmThoughtForOpen(1, payload, {
    getEmotionRecordDetail: async () => ({ completionStatus: 'PARTIAL' }),
    confirmEmotionRecord: async () => { throw conflict },
  }), (error) => error === conflict)
})
