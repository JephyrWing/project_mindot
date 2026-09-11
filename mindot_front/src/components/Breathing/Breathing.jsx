import { useEffect, useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import './Breathing.css'

// 3분 호흡의 전체 진행 시간을 초 단위로 설정.
const breathingDurationSeconds = 180

// 남은 초를 분과 초가 포함된 화면 표시 형식으로 변환.
const formatRemainingTime = (remainingSeconds) => {
  const minutes = Math.floor(remainingSeconds / 60)
  const seconds = String(remainingSeconds % 60).padStart(2, '0')

  return `${minutes}:${seconds}`
}

// 3분 호흡 기능을 단계적으로 확장하기 위한 전용 화면 기본 구조 정의.
function Breathing({
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
  // 화면에 표시할 남은 호흡 시간 상태 관리.
  const [remainingSeconds, setRemainingSeconds] = useState(breathingDurationSeconds)
  // 호흡 타이머의 현재 실행 여부 상태 관리.
  const [isRunning, setIsRunning] = useState(false)
  // 전체 시간에서 경과한 비율 계산.
  const progressPercent = (
    (breathingDurationSeconds - remainingSeconds) / breathingDurationSeconds
  ) * 100
  // 남은 시간과 실행 상태에 맞는 사용자 안내 문구 설정.
  const timerMessage = remainingSeconds === 0
    ? '3분 호흡을 마쳤습니다. 지금의 마음을 천천히 확인해 보세요.'
    : isRunning
      ? '편안한 자세로 천천히 호흡에 집중해 주세요.'
      : remainingSeconds < breathingDurationSeconds
        ? '잠시 멈춘 상태입니다. 준비되면 이어서 시작해 주세요.'
        : '준비가 되면 버튼을 눌러 호흡을 시작해 주세요.'
  // 남은 시간과 실행 상태에 맞는 버튼 이름 설정.
  const timerButtonLabel = isRunning
    ? '잠시 멈추기'
    : remainingSeconds === 0
      ? '다시 시작하기'
      : remainingSeconds < breathingDurationSeconds
        ? '이어서 시작하기'
        : '호흡 시작하기'

  // 타이머 실행 중 1초마다 남은 시간을 감소시키는 처리.
  useEffect(() => {
    if (!isRunning) return undefined

    const timerId = window.setInterval(() => {
      setRemainingSeconds((currentSeconds) => {
        if (currentSeconds <= 1) {
          setIsRunning(false)
          return 0
        }

        return currentSeconds - 1
      })
    }, 1000)

    return () => window.clearInterval(timerId)
  }, [isRunning])

  // 시작과 일시정지 및 완료 후 재시작을 하나의 버튼으로 처리.
  const handleTimerToggle = () => {
    if (remainingSeconds === 0) {
      setRemainingSeconds(breathingDurationSeconds)
      setIsRunning(true)
      return
    }

    setIsRunning((currentState) => !currentState)
  }

  // 공통 네비게이션과 3분 호흡 타이머를 담은 이단계 화면 반환.
  return (
    <div className="breathing-page">
      {/* 다른 서비스 화면과 동일한 공통 네비게이션 배치. */}
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

      <main className="breathing-main">
        {/* 마음 돌봄 추천 화면으로 돌아가는 이전 화면 이동 버튼 배치. */}
        <button className="breathing-back-button" type="button" onClick={onBack}>
          <span aria-hidden="true">←</span>
          뒤로 돌아가기
        </button>

        {/* 3분 호흡 전용 화면의 제목과 간단한 설명 배치. */}
        <section className="breathing-content" aria-labelledby="breathing-title">
          <h1 id="breathing-title">3분 호흡</h1>
          <p>3분 동안 천천히 호흡하며 마음을 가라앉히는 공간입니다.</p>

          {/* 남은 시간과 진행 상태 및 타이머 제어 버튼 배치. */}
          <section className="breathing-timer" aria-labelledby="breathing-timer-title">
            <h2 id="breathing-timer-title">호흡 안내</h2>
            <strong className="breathing-time" aria-live="polite">
              {formatRemainingTime(remainingSeconds)}
            </strong>
            <p className="breathing-timer-message">{timerMessage}</p>

            {/* 전체 3분 중 현재까지 진행한 비율 표시. */}
            <div
              className="breathing-progress"
              role="progressbar"
              aria-label="3분 호흡 진행률"
              aria-valuemin="0"
              aria-valuemax="100"
              aria-valuenow={Math.round(progressPercent)}
            >
              <span style={{ width: `${progressPercent}%` }} />
            </div>

            <button
              className="breathing-control-button"
              type="button"
              onClick={handleTimerToggle}
            >
              {timerButtonLabel}
            </button>
          </section>
        </section>
      </main>
    </div>
  )
}

export default Breathing
