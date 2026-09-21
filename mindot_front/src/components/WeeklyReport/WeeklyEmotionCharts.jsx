import { useMemo, useRef, useState } from 'react'
import { emotionCategory, emotionColor, reportEmotionLabel } from '../../utils/records/emotionColors.js'
import { countEmotions, pointRadius, scatterRecords, timeBuckets, weekdays } from '../../utils/reports/weeklyReportData.js'
import './WeeklyEmotionCharts.css'

const recordSummary = (r) => `${reportEmotionLabel(r.emotion)} · ${r.intensity === null ? '강도 미입력' : `강도 ${r.intensity}/10`} · ${r.date} ${weekdays[r.day]} ${r.time}`

function StackedDistribution({ title, groups }) {
  const maximum = Math.max(1, ...groups.map((g) => g.records.length))
  return <section className="weekly-stacked" aria-label={title}>
    <h3>{title}</h3>
    <ul>{groups.map(({ label, records }) => <li key={label} data-total={records.length}>
      <div className="weekly-stacked-heading"><span>{label}</span><strong>{records.length}건</strong></div>
      <div className="weekly-stacked-track" aria-hidden="true">
        <div className="weekly-stacked-bar" style={{ width: `${records.length / maximum * 100}%` }}>
          {countEmotions(records).map(([emotion, count]) => <span key={emotion} data-count={count}
            style={{ width: `${count / records.length * 100}%`, background: emotionColor(emotion) }} />)}
        </div>
      </div>
      <p className="weekly-stacked-counts">{records.length
        ? countEmotions(records).map(([emotion, count]) => `${reportEmotionLabel(emotion)} ${count}건`).join(' · ')
        : '기록 없음'}</p>
    </li>)}</ul>
  </section>
}

export default function WeeklyEmotionCharts({ records, timezone, onRecordDetail }) {
  const allPoints = useMemo(() => scatterRecords(records), [records])
  const [filter, setFilter] = useState('all')
  // Lay out once before filtering so unchanged points never shift when toggling the filter.
  const points = allPoints.filter((r) => filter === 'all' || emotionCategory(r.emotion) === filter)
  const [selectedId, setSelectedId] = useState(null)
  const touchUntil = useRef(0)
  const selected = points.find((r) => r.emotionRecordId === selectedId)
  const emotions = countEmotions(points)
  return <>
    <section className="weekly-scatter" aria-labelledby="weekly-scatter-title">
      <header><h2 id="weekly-scatter-title">한 주의 감정 기록</h2><span aria-live="polite">{filter === 'all' ? `${records.length}건` : `${records.length}건 중 ${points.length}건 표시`} · {timezone}</span></header>
      <p className="weekly-chart-guide">점 하나는 기록 하나입니다. 위에서 아래로 하루의 시간을 따라 살펴보세요.</p>
      {records.length === 0 && <p className="weekly-chart-empty">이번 주에는 감정 기록이 없습니다.</p>}
      {records.length > 0 && points.length === 0 && <p className="weekly-chart-empty" role="status">선택한 감정에 해당하는 기록이 없습니다.</p>}
      <div className="weekly-scatter-layout">
      <div className="weekly-scatter-frame">
        <div className="weekly-scatter-days" aria-hidden="true">{weekdays.map((day) => <span key={day}>{day[0]}</span>)}</div>
        <div className="weekly-scatter-times" aria-hidden="true">{[0, 4, 8, 12, 16, 20, 24].map((hour) =>
          <span key={hour} style={{ top: `${hour / 24 * 100}%` }}>{String(hour).padStart(2, '0')}:00</span>)}</div>
        <div className="weekly-scatter-plot" role="group" aria-label="요일과 발생 시각별 감정 기록">
          {[0, 4, 8, 12, 16, 20, 24].map((hour) => <div key={hour} className="weekly-scatter-gridline" style={{ top: `${hour / 24 * 100}%` }} />)}
          {points.map((r) => <button key={r.emotionRecordId} type="button" className="weekly-scatter-hit"
            data-record-id={r.emotionRecordId} aria-label={recordSummary(r)} aria-pressed={selectedId === r.emotionRecordId}
            style={{ left: `${r.x}%`, top: `${r.y}%` }}
            onMouseEnter={() => setSelectedId(r.emotionRecordId)} onFocus={() => setSelectedId(r.emotionRecordId)}
            onPointerDown={(event) => {
              if (event.pointerType === 'touch' || event.pointerType === 'pen') {
                touchUntil.current = Date.now() + 1500
                setSelectedId(r.emotionRecordId)
              } else touchUntil.current = 0
            }}
            onClick={(event) => {
              if (Date.now() < touchUntil.current || event.nativeEvent.pointerType === 'touch') setSelectedId(r.emotionRecordId)
              else onRecordDetail?.(r.emotionRecordId)
            }}>
            <span className="weekly-scatter-dot" data-intensity={r.intensity ?? 'missing'} style={{
              width: pointRadius(r.intensity) * 2, height: pointRadius(r.intensity) * 2,
              background: r.intensity === null ? 'white' : emotionColor(r.emotion), borderColor: emotionColor(r.emotion),
            }} />
          </button>)}
          {selected && <div className="weekly-scatter-tooltip" role="status" style={{
            left: `clamp(0px, calc(${selected.x}% - 110px), max(0px, calc(100% - 220px)))`,
            top: `${selected.y}%`, transform: selected.y > 50 ? 'translateY(calc(-100% - 24px))' : 'translateY(24px)',
          }}>
            <strong>{reportEmotionLabel(selected.emotion)}</strong>
            <span>{selected.intensity === null ? '강도 미입력' : `강도 ${selected.intensity}/10`}</span>
            <span>{selected.date} {weekdays[selected.day]} {selected.time}</span>
            <div><button type="button" onClick={() => onRecordDetail?.(selected.emotionRecordId)}>상세 보기</button>
              <button type="button" aria-label="기록 요약 닫기" onClick={() => setSelectedId(null)}>닫기</button></div>
          </div>}
        </div>
      </div>
      <fieldset className="weekly-scatter-filter">
        <legend>감정 필터</legend>
        {[['all', '전체 표시'], ['negative', '부정적 감정'], ['positive', '긍정적 감정']].map(([value, label]) =>
          <label key={value} className={`weekly-scatter-filter-option is-${value}`}>
            <input type="radio" name="weekly-scatter-filter" value={value} checked={filter === value}
              onChange={() => { setFilter(value); setSelectedId(null); touchUntil.current = 0 }} />
            <span>{label}</span>
          </label>)}
        <p>미등록 감정·기타·감정 미입력은 전체 표시에서 볼 수 있어요.</p>
      </fieldset>
      </div>
      <h3 className="weekly-emotion-legend-heading">감정 분포 · 범례</h3>
      <ul className="weekly-emotion-legend" aria-label="이번 주 감정 범례">{emotions.map(([emotion, count]) =>
        <li key={emotion}><i style={{ background: emotionColor(emotion) }} aria-hidden="true" />{reportEmotionLabel(emotion)} <span>{count}건</span></li>)}</ul>
      <p className="weekly-chart-guide">점의 면적은 강도(0~10)를 나타냅니다. 작은 채운 점은 0, 테두리 원은 강도 미입력입니다.
        점에 마우스를 올리거나 터치하면 요약을 확인할 수 있습니다.</p>
      <details className="weekly-scatter-records"><summary>겹친 점도 빠짐없이 보기 · {filter === 'all' ? '전체' : '선택한 감정'} 기록 {points.length}건</summary>
        <ul>{points.map((r) => <li key={r.emotionRecordId}><i style={{ background: emotionColor(r.emotion) }} aria-hidden="true" />
          <button type="button" onFocus={() => setSelectedId(r.emotionRecordId)} onClick={() => onRecordDetail?.(r.emotionRecordId)}>
            {recordSummary(r)}<span>상세 보기</span></button></li>)}</ul>
      </details>
    </section>
    <section className="weekly-emotion-distributions" aria-labelledby="weekly-emotion-distributions-title">
      <h2 id="weekly-emotion-distributions-title">기록 분포</h2>
      {records.length === 0 && <p>집계할 감정 기록이 없습니다.</p>}
      <div className="weekly-emotion-distribution-grid">
        <StackedDistribution title="요일 분포" groups={weekdays.map((label, day) => ({ label, records: records.filter((r) => r.day === day) }))} />
        <StackedDistribution title="시간대 분포" groups={Object.entries(timeBuckets).map(([bucket, label]) => ({ label, records: records.filter((r) => r.timeBucket === bucket) }))} />
      </div>
    </section>
  </>
}
