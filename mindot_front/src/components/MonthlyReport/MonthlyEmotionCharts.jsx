import { useState } from 'react'
import { compareReportEmotions, emotionColor, reportEmotionLabel } from '../../utils/records/emotionColors.js'
import './MonthlyEmotionCharts.css'

const contexts = { SOCIAL_EVALUATION: '사회적 평가', PERFORMANCE: '발표·시험', PROMISE: '약속', MISTAKE: '실수',
  CONFLICT: '갈등', REJECTION: '거절·소외', WORK: '업무', STUDY: '학업', HEALTH: '건강', DAILY_LIFE: '일상', OTHER: '기타' }
const key = (emotion) => JSON.stringify(emotion)
const percent = (count, total) => `${(total ? count / total * 100 : 0).toFixed(1)}%`
const ordered = (counts, order) => order.map(({ emotion }) => counts.find((c) => c.emotion === emotion)).filter(Boolean)

function Legend({ counts, total, order }) {
  return total === 0 ? <p>기록 없음</p> : <ul className="monthly-composition-legend">
    {ordered(counts, order).map(({ emotion, count }) => <li key={key(emotion)}>
      <i style={{ background: emotionColor(emotion) }} aria-hidden="true" />
      <span>{reportEmotionLabel(emotion)} · {count}건 · {percent(count, total)}</span>
    </li>)}
  </ul>
}
function Bar({ counts, total, scale = total, order }) {
  return <div className="monthly-composition-track" aria-hidden="true">
    <div className="monthly-composition-bar" data-total={total} style={{ width: `${scale ? total / scale * 100 : 0}%` }}>
      {ordered(counts, order).map(({ emotion, count }) => <span key={key(emotion)} data-count={count}
        style={{ width: `${total ? count / total * 100 : 0}%`, background: emotionColor(emotion) }} />)}
    </div>
  </div>
}
function Distribution({ title, group, scale, order }) {
  // Native details supports pointer, touch and keyboard without hiding information in hover-only content.
  return <details className="monthly-composition-group" open>
    <summary>{title} · {group.recordCount}건</summary>
    <Bar counts={group.emotions} total={group.recordCount} scale={scale} order={order} />
    <Legend counts={group.emotions} total={group.recordCount} order={order} />
  </details>
}
export default function MonthlyEmotionCharts({ composition }) {
  const [selectedDate, setSelectedDate] = useState('')
  const { days, contexts: groups, halves, recordCount } = composition
  const emotions = [...composition.emotions].sort((a, b) => compareReportEmotions(a.emotion, b.emotion))
  const selected = days.find((g) => g.value === selectedDate) ?? days.find((g) => g.recordCount > 0) ?? days[0]
  const maximum = Math.max(1, ...days.map((g) => g.recordCount))
  const contextMaximum = Math.max(1, ...groups.map((g) => g.recordCount))
  return <div className="monthly-composition">
    <section aria-labelledby="monthly-half-trend-title">
      <h2 id="monthly-half-trend-title">월 초반·후반 비교</h2>
      <p>각 기간의 총기록 수를 기준으로 한 감정 비율입니다. 막대 전체는 100%를 나타냅니다.</p>
      {halves.map((half, index) => <Distribution key={half.periodStart} title={`${index === 0 ? '월 초반' : '월 후반'} ${half.periodStart} ~ ${half.periodEnd}`}
        group={half} order={emotions} />)}
    </section>
    <section aria-labelledby="monthly-chart-title">
      <h2 id="monthly-chart-title">날짜별 감정 기록 수</h2>
      <p>세로축: 기록 건수(건) · 가로축: 날짜(일) · 날짜를 선택하면 감정 구성을 확인할 수 있습니다.</p>
      <div className="monthly-count-chart-scroll" tabIndex={0} aria-label="월 전체 날짜 그래프 가로 스크롤">
        <div className="monthly-count-chart">
          <div className="monthly-count-axis" aria-hidden="true"><span>{maximum}건</span><span>0건</span></div>
          {days.map((day) => <button type="button" key={day.value} className="monthly-count-day"
            aria-label={`${day.value}, 기록 ${day.recordCount}건`} aria-pressed={selected?.value === day.value}
            onMouseEnter={() => setSelectedDate(day.value)} onFocus={() => setSelectedDate(day.value)} onClick={() => setSelectedDate(day.value)}>
            <span className="monthly-count-total">{day.recordCount}</span>
            <span className="monthly-count-track" aria-hidden="true">
              <span className="monthly-count-bar" style={{ height: `${day.recordCount / maximum * 100}%` }}>
                {ordered(day.emotions, emotions).map(({ emotion, count }) => <span key={key(emotion)} data-count={count}
                  style={{ height: `${count / day.recordCount * 100}%`, background: emotionColor(emotion) }} />)}
              </span>
            </span>
            <span>{Number(day.value.slice(-2))}</span>
          </button>)}
        </div>
      </div>
      {selected && <div className="monthly-count-detail" aria-live="polite">
        <h3>{selected.value} · 총 {selected.recordCount}건</h3>
        <Legend counts={selected.emotions} total={selected.recordCount} order={emotions} />
      </div>}
    </section>
    <section aria-labelledby="monthly-distributions-title">
      <h2 id="monthly-distributions-title">감정·상황 분포</h2>
      <h3>기록한 감정</h3>
      <Distribution title="월 전체" group={{ emotions, recordCount }} order={emotions} />
      <h3>기록한 상황</h3>
      <p>막대 길이는 실제 기록 건수에 비례합니다. 감정 비율은 해당 상황의 기록 수를 기준으로 합니다.</p>
      {groups.length === 0 && <p>기록 없음</p>}
      {groups.map((group) => <Distribution key={key(group.value)} title={group.value === null ? '상황 미분류' : contexts[group.value] ?? group.value}
        group={group} scale={contextMaximum} order={emotions} />)}
    </section>
  </div>
}
