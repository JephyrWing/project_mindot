import test from 'node:test'
import assert from 'node:assert/strict'
import { bucketAtMinute, countEmotions, isWeekStart, pointRadius, scatterRecords, weeklyRecords } from './weeklyReportData.js'
import { emotionCategory, emotionColors, emotionColor, reportEmotionLabel } from '../records/emotionColors.js'
import { primaryEmotionLabels } from '../records/emotions.js'

const row = (id, occurredAt, primaryIntensity = null, primaryEmotionCode = null) => ({ emotionRecordId: id, occurredAt, primaryIntensity, primaryEmotionCode })
const parse = (rows, count = rows.length) => weeklyRecords({ emotionRecordEvidences: rows, recordCount: count }, '2026-09-14', 'Asia/Seoul')

test('KST week boundaries use the account timezone, including Monday midnight and Sunday 23:59', () => {
  const records = parse([row(1, '2026-09-13T15:00:00Z'), row(2, '2026-09-20T14:59:00Z')])
  assert.deepEqual(records.map((r) => [r.day, r.minute, r.timeBucket]), [[0, 0, 'DAWN'], [6, 1439, 'NIGHT']])
  assert.throws(() => parse([row(3, '2026-09-20T15:00:00Z')]), /기간/)
  assert.throws(() => parse([row(3, '2026-09-13T14:59:00Z')]), /기간/)
  const ny = weeklyRecords({ recordCount: 1, emotionRecordEvidences: [row(1, '2026-09-14T04:00:00Z')] }, '2026-09-14', 'America/New_York')
  assert.equal(ny[0].minute, 0)
})
test('more than one page of records, stable ID deduplication and no truncation', () => {
  const rows = Array.from({ length: 120 }, (_, i) => row(i + 1, '2026-09-14T03:00:00Z', i % 11, i % 2 ? '짜증' : null))
  const records = parse([...rows, rows[0]], 120)
  assert.equal(records.length, 120)
  assert.equal(countEmotions(records).reduce((sum, [, n]) => sum + n, 0), 120)
  const points = scatterRecords(records)
  assert.deepEqual(points, scatterRecords(parse([...rows].reverse())))
  assert.equal(new Set(points.map((r) => r.x)).size, 5)
  assert.equal(new Set(points.map((r) => r.y)).size, 1)
})
test('zero, ten and absent intensity remain different; dot area grows linearly', () => {
  const records = parse([0, 10, null].map((n, i) => row(i + 1, '2026-09-14T03:00:00Z', n)))
  assert.deepEqual(records.map((r) => r.intensity), [0, 10, null])
  assert.ok(Math.abs((pointRadius(10) ** 2 - pointRadius(0) ** 2) / 2 - (pointRadius(5) ** 2 - pointRadius(0) ** 2)) < 1e-10)
  assert.ok(pointRadius(0) > 0)
})
test('all dropdown emotions have fixed colors, custom names and missing emotion stay neutral', () => {
  assert.deepEqual(Object.keys(emotionColors).sort(), Object.keys(primaryEmotionLabels).sort())
  assert.equal(emotionColor('설명할 수 없음'), emotionColor('OTHER'))
  assert.equal(emotionColor(null), emotionColor('OTHER'))
  assert.equal(reportEmotionLabel('독특한 마음'), '독특한 마음')
  assert.equal(reportEmotionLabel('OTHER'), '기타')
  assert.equal(reportEmotionLabel(''), '감정 미입력')
  assert.equal(reportEmotionLabel('ANXIETY'), '불안')
})
test('time bucket boundaries match the backend enum', () => {
  assert.deepEqual([0, 359, 360, 719, 720, 1079, 1080, 1259, 1260, 1439].map(bucketAtMinute),
    ['DAWN', 'DAWN', 'MORNING', 'MORNING', 'AFTERNOON', 'AFTERNOON', 'EVENING', 'EVENING', 'NIGHT', 'NIGHT'])
})
test('emotion filters classify only registered negative/positive values, including 짜증', () => {
  for (const code of ['ANXIETY', 'FEAR', 'ANGER', 'FRUSTRATION', 'SADNESS', 'DISAPPOINTMENT', 'SHAME', 'GUILT', 'LONELINESS', '짜증']) assert.equal(emotionCategory(code), 'negative')
  for (const code of ['JOY', 'RELIEF', 'ACHIEVEMENT', 'CALM', 'GRATITUDE', 'EXCITEMENT']) assert.equal(emotionCategory(code), 'positive')
  for (const code of ['OTHER', '', null, '복잡한 마음', '기쁜 마음']) assert.equal(emotionCategory(code), 'neutral')
})
test('missing old cache fields and inconsistent counts are errors, not empty weeks', () => {
  assert.throws(() => weeklyRecords({ recordCount: 0 }, '2026-09-14', 'Asia/Seoul'), /갱신/)
  assert.throws(() => parse([], 5), /건수/)
  assert.deepEqual(parse([]), [])
  assert.equal(isWeekStart('2026-09-14'), true)
  assert.equal(isWeekStart('2026-09-15'), false)
  assert.equal(isWeekStart('2026-02-30'), false)
})
