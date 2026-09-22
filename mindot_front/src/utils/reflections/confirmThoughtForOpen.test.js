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

test('COMPLETE without a thought uses the record PATCH and recovers a lost response', async () => {
  let reads = 0, writes = 0
  const result = await confirmThoughtForOpen(1, payload, {
    getEmotionRecordDetail: async () => ++reads === 1 ? { completionStatus: 'COMPLETE', automaticThought: null } : complete,
    updateEmotionRecord: async (id, thought) => { assert.deepEqual(thought, { automaticThought: payload.automaticThought }); writes++; throw new Error('lost response') },
    confirmEmotionRecord: async () => { throw new Error('must not confirm COMPLETE') },
  })
  assert.equal(result, complete)
  assert.equal(writes, 1)
})

test('a conflicting first writer after PATCH is reported without another mutation', async () => {
  let reads = 0, writes = 0
  await assert.rejects(confirmThoughtForOpen(1, payload, {
    getEmotionRecordDetail: async () => ++reads === 1 ? { completionStatus: 'COMPLETE' } : { ...complete, automaticThought: '먼저 저장된 다른 생각' },
    updateEmotionRecord: async () => { writes++; throw Object.assign(new Error('conflict'), { response: { status: 409 } }) },
  }), { message: 'confirmed_thought_conflict' })
  assert.equal(writes, 1)
})
