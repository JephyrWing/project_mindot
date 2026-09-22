import assert from 'node:assert/strict'
import test from 'node:test'
import {
  classifyDistortionChanges,
  getAfterDistortionCodes,
  getBeforeDistortionCodes,
  normalizeDistortionCodes,
} from './distortionComparison.js'

test('인지왜곡 라벨을 제거·지속·신규로 구분한다', () => {
  assert.deepEqual(
    classifyDistortionChanges(
      ['ALL_OR_NOTHING_THINKING', 'PERSONALIZATION'],
      ['ALL_OR_NOTHING_THINKING', 'MIND_READING'],
    ),
    {
      removed: ['PERSONALIZATION'],
      persisted: ['ALL_OR_NOTHING_THINKING'],
      new: ['MIND_READING'],
    },
  )
})

test('거절·중복 라벨을 제외하고 기존 결과의 확정 BEFORE 라벨을 복원한다', () => {
  const result = {
    suggestions: [
      { code: 'LABELING' },
      { code: 'MIND_READING' },
    ],
    reviews: [
      { code: 'LABELING', reviewStatus: 'CONFIRMED' },
      { code: 'MIND_READING', reviewStatus: 'REJECTED' },
    ],
  }

  assert.deepEqual(getBeforeDistortionCodes(result), ['LABELING'])
  assert.deepEqual(getAfterDistortionCodes(result), [])
  assert.deepEqual(
    normalizeDistortionCodes([
      { code: 'LABELING', reviewStatus: 'CONFIRMED' },
      { code: 'LABELING', reviewStatus: 'CONFIRMED' },
      { code: 'MIND_READING', reviewStatus: 'REJECTED' },
    ]),
    ['LABELING'],
  )
})
