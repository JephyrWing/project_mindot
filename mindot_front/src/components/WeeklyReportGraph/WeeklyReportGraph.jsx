import { useEffect, useMemo, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import {
  generateWeeklyReport,
  getWeeklyReport,
} from '../../utils/reports/reportsApi.js'
import './WeeklyReportGraph.css'

// 사용자 시간대의 날짜를 백엔드 요청용 연월일 형식으로 변환.
const toLocalDateValue = (date) => {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')

  return `${year}-${month}-${day}`
}

// 선택한 주의 월요일과 일요일을 사용자 표시 기간으로 계산.
const getWeekRange = (weekOffset) => {
  const selectedDate = new Date()
  selectedDate.setHours(12, 0, 0, 0)
  selectedDate.setDate(selectedDate.getDate() + weekOffset * 7)

  const dayOfWeek = selectedDate.getDay()
  const mondayOffset = dayOfWeek === 0 ? -6 : 1 - dayOfWeek
  const weekStart = new Date(selectedDate)
  weekStart.setDate(selectedDate.getDate() + mondayOffset)

  const weekEnd = new Date(weekStart)
  weekEnd.setDate(weekStart.getDate() + 6)

  const dateFormatter = new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })

  return {
    weekStart: toLocalDateValue(weekStart),
    label: `${dateFormatter.format(weekStart)} ~ ${dateFormatter.format(weekEnd)}`,
  }
}

// 월요일부터 일요일까지 그래프에 표시할 요일 순서 설정.
const graphWeekdays = [
  { day: '월', dayIndex: 1 },
  { day: '화', dayIndex: 2 },
  { day: '수', dayIndex: 3 },
  { day: '목', dayIndex: 4 },
  { day: '금', dayIndex: 5 },
  { day: '토', dayIndex: 6 },
  { day: '일', dayIndex: 0 },
]

// 감정 기록 근거 목록을 요일별로 묶고 대표 감정 강도의 평균값 계산.
const createWeeklyGraphItems = (emotionRecordEvidences = []) => {
  const intensitiesByDay = Array.from({ length: 7 }, () => [])

  emotionRecordEvidences.forEach((record) => {
    const occurredDate = new Date(record.occurredAt)
    const intensity = Number(record.primaryIntensity)

    if (Number.isNaN(occurredDate.getTime()) || !Number.isFinite(intensity)) return

    intensitiesByDay[occurredDate.getDay()].push(intensity)
  })

  return graphWeekdays.map(({ day, dayIndex }) => {
    const intensities = intensitiesByDay[dayIndex]

    if (intensities.length === 0) {
      return { day, value: null, recordCount: 0 }
    }

    const averageIntensity = intensities.reduce(
      (totalIntensity, currentIntensity) => totalIntensity + currentIntensity,
      0,
    ) / intensities.length

    return {
      day,
      value: Number(averageIntensity.toFixed(1)),
      recordCount: intensities.length,
    }
  })
}

// 주간 감정 그래프 기능을 단계적으로 추가하기 위한 기본 화면 정의.
function WeeklyReportGraph({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onDailyCare,
  onBack,
  onHome,
}) {
  // 현재 주를 기준으로 사용자가 이동한 주간 위치 상태 관리.
  const [weekOffset, setWeekOffset] = useState(0)
  // 백엔드에서 조회하거나 생성한 선택 주의 리포트 상태 관리.
  const [report, setReport] = useState(null)
  // 선택 주의 리포트 API 호출 진행 여부 상태 관리.
  const [isLoading, setIsLoading] = useState(true)
  // 선택 주의 리포트 API 호출 실패 안내 상태 관리.
  const [loadError, setLoadError] = useState('')
  // 선택한 주의 월요일부터 일요일까지 표시할 기간 계산.
  const selectedWeek = getWeekRange(weekOffset)
  // API 응답의 감정 기록 근거를 요일별 평균 강도 그래프 항목으로 변환.
  const weeklyGraphItems = useMemo(
    () => createWeeklyGraphItems(report?.emotionRecordEvidences),
    [report],
  )

  // 선택 주 변경 시 저장된 리포트 조회와 미생성 리포트 생성 요청 처리.
  useEffect(() => {
    let isActive = true

    const loadWeeklyReport = async () => {
      setIsLoading(true)
      setLoadError('')
      setReport(null)

      try {
        const savedReport = await getWeeklyReport(selectedWeek.weekStart)

        if (isActive) setReport(savedReport)
      } catch (getError) {
        if (!isActive) return

        if (getError.response?.status !== 404) {
          setLoadError('주간 리포트를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.')
          setIsLoading(false)
          return
        }

        try {
          const generatedReport = await generateWeeklyReport(selectedWeek.weekStart)

          if (isActive) setReport(generatedReport)
        } catch (generateError) {
          if (!isActive) return

          setLoadError(
            generateError.response?.status === 409
              ? '선택한 주에 감정 기록이 없어 리포트를 만들 수 없습니다.'
              : '주간 리포트를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.',
          )
        }
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadWeeklyReport()

    return () => {
      isActive = false
    }
  }, [selectedWeek.weekStart])

  // 공통 네비게이션과 그래프 표시 예정 영역으로 구성한 기본 화면 반환.
  return (
    <main className="weekly-report-graph-page">
      <Navbar
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={onLogin}
        onLogout={onLogout}
        onSignUp={onSignUp}
        onEmotionHistory={onEmotionHistory}
        onCenter={onCenter}
        onDailyCare={onDailyCare}
        onHome={onHome}
      />

      <div className="weekly-report-graph-content">
        <section className="weekly-report-graph-card" aria-labelledby="weekly-graph-title">
          <BrandLogo className="weekly-report-graph-logo" onClick={onHome} />
          <h1 id="weekly-graph-title">주간 감정 그래프</h1>
          <p className="weekly-report-graph-description">
            한 주 동안의 감정 변화를 그래프로 확인하는 공간입니다.
          </p>

          {/* 선택한 주의 기간과 이전 및 다음 주 이동 기능 배치. */}
          <div className="weekly-report-graph-period">
            <span>선택 기간</span>
            <strong>{selectedWeek.label}</strong>
          </div>
          <div
            className="weekly-report-graph-navigation"
            aria-label="주간 그래프 기간 선택"
          >
            <button
              type="button"
              onClick={() => setWeekOffset((currentOffset) => currentOffset - 1)}
            >
              ← 이전 주
            </button>
            <button
              type="button"
              onClick={() => setWeekOffset((currentOffset) => Math.min(currentOffset + 1, 0))}
              disabled={weekOffset === 0}
            >
              다음 주 →
            </button>
          </div>

          {/* 선택한 주의 리포트 API 호출 결과 상태 표시. */}
          <section
            className="weekly-report-graph-chart"
            aria-labelledby="weekly-report-graph-chart-title"
          >
            <header className="weekly-report-graph-chart-heading">
              <h2 id="weekly-report-graph-chart-title">요일별 감정 강도</h2>
              <span>0~10점</span>
            </header>

            {isLoading && (
              <p className="weekly-report-graph-note" role="status">
                주간 리포트를 불러오고 있습니다.
              </p>
            )}
            {loadError && (
              <p className="weekly-report-graph-note" role="alert">
                {loadError}
              </p>
            )}
            {report && !isLoading && !loadError && (
              <>
                {/* 실제 감정 기록의 요일별 평균 강도를 일곱 개 막대로 표시. */}
                <div className="weekly-report-graph-bars">
                  {weeklyGraphItems.map((item) => (
                    <div
                      className="weekly-report-graph-item"
                      key={item.day}
                      aria-label={item.recordCount > 0
                        ? `${item.day}요일 감정 강도 평균 ${item.value}점, 기록 ${item.recordCount}건`
                        : `${item.day}요일 감정 기록 없음`}
                    >
                      <span className="weekly-report-graph-value">
                        {item.value ?? '-'}
                      </span>
                      <div className="weekly-report-graph-track" aria-hidden="true">
                        <span style={{ height: `${(item.value ?? 0) * 10}%` }} />
                      </div>
                      <strong>{item.day}</strong>
                    </div>
                  ))}
                </div>

                <p className="weekly-report-graph-note" role="status">
                  감정 기록 {report.recordCount}건의 요일별 평균 강도입니다.
                </p>
              </>
            )}
          </section>

          <button className="weekly-report-graph-back" type="button" onClick={onBack}>
            주간 리포트로 돌아가기
          </button>
        </section>
      </div>
    </main>
  )
}

export default WeeklyReportGraph
