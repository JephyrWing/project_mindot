export const weekdays = ['월요일', '화요일', '수요일', '목요일', '금요일', '토요일', '일요일']
export const timeBuckets = { DAWN: '새벽', MORNING: '아침', AFTERNOON: '오후', EVENING: '저녁', NIGHT: '밤' }

export const dateParts = (instant, timezone) => Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
  timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
}).formatToParts(new Date(instant)).map(({ type, value }) => [type, value]))
export const dateInZone = (instant, timezone) => {
  const p = dateParts(instant, timezone)
  return `${p.year}-${p.month}-${p.day}`
}
export const addDays = (date, days) => new Date(Date.parse(`${date}T12:00:00Z`) + days * 86400000).toISOString().slice(0, 10)
export const currentWeekStart = (timezone = 'Asia/Seoul') => {
  const date = dateInZone(new Date(), timezone)
  return addDays(date, -((new Date(`${date}T12:00:00Z`).getUTCDay() + 6) % 7))
}
export const isWeekStart = (value) => /^\d{4}-\d{2}-\d{2}$/.test(value ?? '')
  && !Number.isNaN(Date.parse(`${value}T00:00:00Z`))
  && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value
  && new Date(`${value}T00:00:00Z`).getUTCDay() === 1

// Same boundaries as the backend TimeBucket enum (00/06/12/18/21).
export const bucketAtMinute = (minute) => minute < 360 ? 'DAWN' : minute < 720 ? 'MORNING'
  : minute < 1080 ? 'AFTERNOON' : minute < 1260 ? 'EVENING' : 'NIGHT'

// The existing backend includes ALL weekly records in this snapshot, without a limit.
// Missing/truncated old evidence is regenerated as a whole, never mixed with live list data.
export const weeklyRecords = (report, weekStart, timezone) => {
  if (!timezone || !Array.isArray(report?.emotionRecordEvidences)) {
    throw new Error('주간 리포트 데이터 갱신이 필요합니다.')
  }
  if (report.periodStart && report.periodStart !== weekStart) throw new Error('리포트 기간이 일치하지 않습니다.')
  const records = [...new Map(report.emotionRecordEvidences.map((r) => [String(r.emotionRecordId), r])).values()]
    .map((r) => {
      if (!r.emotionRecordId || !r.occurredAt) throw new Error('불완전한 기록입니다.')
      const p = dateParts(r.occurredAt, timezone)
      const date = `${p.year}-${p.month}-${p.day}`
      if (date < weekStart || date >= addDays(weekStart, 7)) throw new Error('리포트 기간이 일치하지 않습니다.')
      const intensity = Number.isFinite(r.primaryIntensity) && r.primaryIntensity >= 0 && r.primaryIntensity <= 10 ? r.primaryIntensity : null
      const minute = Number(p.hour) * 60 + Number(p.minute)
      return { ...r, date, time: `${p.hour}:${p.minute}`, minute, timeBucket: bucketAtMinute(minute),
        day: (new Date(`${date}T12:00:00Z`).getUTCDay() + 6) % 7,
        emotion: r.primaryEmotionCode?.trim() ? r.primaryEmotionCode : '', intensity }
    }).sort((a, b) => a.day - b.day || a.minute - b.minute || String(a.emotionRecordId).localeCompare(String(b.emotionRecordId), 'en', { numeric: true }))
  if (records.length !== report.recordCount) throw new Error('리포트 기록 건수가 일치하지 않습니다.')
  return records
}

// Area = π(25 + 12 × intensity); null is a hollow minimum-sized mark, not intensity zero.
export const pointRadius = (intensity) => Math.sqrt(25 + 12 * (intensity ?? 0))
export const scatterRecords = (records) => {
  const lastMinutes = Array.from({ length: 7 }, () => Array(5).fill(-Infinity))
  const lanes = [0, -1, 1, -2, 2]
  return records.map((r) => {
    const last = lastMinutes[r.day]
    let lane = last.findIndex((minute) => r.minute - minute >= 100)
    if (lane < 0) lane = last.indexOf(Math.min(...last))
    last[lane] = r.minute
    return { ...r, x: ((r.day + 0.5 + lanes[lane] * 0.17) / 7) * 100, y: r.minute / 1440 * 100 }
  })
}
export const countEmotions = (records) => {
  const counts = new Map()
  for (const r of records) counts.set(r.emotion, (counts.get(r.emotion) ?? 0) + 1)
  return [...counts].sort(([a], [b]) => a.localeCompare(b, 'ko'))
}
