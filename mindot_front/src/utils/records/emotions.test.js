import test from 'node:test'
import assert from 'node:assert/strict'
import { emotionLabel, emotionSelection, selectedEmotion, emotionError, CUSTOM_EMOTION } from './emotions.js'
import { automaticThoughtError } from '../reflections/automaticThought.js'

test('custom strings retain spelling; only exact labels become known codes', () => {
  for (const name of ['먹먹함', 'MixedCase', '불안한 마음', '__proto__', 'constructor']) {
    assert.equal(emotionLabel(name), name)
    assert.equal(selectedEmotion(emotionSelection(name)), name)
  }
  assert.equal(selectedEmotion({ primaryEmotionCode: CUSTOM_EMOTION, customEmotion: ' 불안 ' }), 'ANXIETY')
  assert.equal(selectedEmotion({ primaryEmotionCode: 'JOY', customEmotion: '먹먹함' }), 'JOY')
  assert.equal(emotionLabel('OTHER'), '기타')
  assert.equal(emotionSelection('OTHER').primaryEmotionCode, 'OTHER')
})
test('emotion length and optional thought boundaries', () => {
  assert.ok(emotionError(''))
  assert.equal(emotionError('가'.repeat(50)), '')
  assert.ok(emotionError('가'.repeat(51)))
  assert.equal(automaticThoughtError('   ', { required: false }), '')
  assert.ok(automaticThoughtError('   '))
  assert.equal(automaticThoughtError('가'.repeat(4000), { required: false }), '')
  assert.ok(automaticThoughtError('가'.repeat(4001), { required: false }))
})
