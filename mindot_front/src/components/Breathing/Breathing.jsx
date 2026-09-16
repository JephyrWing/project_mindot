import { useEffect, useRef, useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import './Breathing.css'

// 3분 호흡의 전체 진행 시간을 초 단위로 설정.
const breathingDurationSeconds = 180
// 들이쉬기와 멈추기 및 내쉬기로 구성한 한 번의 호흡 주기 설정.
const breathingCycleSeconds = 14

// 남은 초를 분과 초가 포함된 화면 표시 형식으로 변환.
const formatRemainingTime = (remainingSeconds) => {
  const minutes = Math.floor(remainingSeconds / 60)
  const seconds = String(remainingSeconds % 60).padStart(2, '0')

  return `${minutes}:${seconds}`
}

// 3분 타이머의 경과 시간으로 현재 호흡 단계와 남은 초 계산.
const getBreathingPhase = (elapsedSeconds, remainingSeconds, isRunning) => {
  if (remainingSeconds === 0) {
    return {
      name: '완료',
      remaining: null,
      className: 'is-complete',
      instruction: '자연스러워진 호흡과 지금의 마음을 천천히 확인해 보세요.',
    }
  }

  if (elapsedSeconds === 0 && !isRunning) {
    return {
      name: '준비',
      remaining: null,
      className: 'is-ready',
      instruction: '어깨의 힘을 풀고 편안한 자세를 만들어 주세요.',
    }
  }

  const cycleSecond = elapsedSeconds % breathingCycleSeconds

  if (cycleSecond < 4) {
    return {
      name: '들이쉬기',
      remaining: 4 - cycleSecond,
      className: 'is-inhale',
      instruction: '코로 천천히 숨을 들이마셔요.',
    }
  }

  if (cycleSecond < 8) {
    return {
      name: '멈추기',
      remaining: 8 - cycleSecond,
      className: 'is-hold',
      instruction: '몸에 힘을 주지 않고 잠시 머물러요.',
    }
  }

  return {
    name: '내쉬기',
    remaining: 14 - cycleSecond,
    className: 'is-exhale',
    instruction: '입으로 길고 부드럽게 숨을 내쉬어요.',
  }
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
  // 브라우저 화면 갱신 지연에도 실제 종료 시각을 유지하기 위한 기준 시각 저장.
  const timerDeadlineRef = useRef(null)
  // 전체 시간에서 경과한 비율 계산.
  const progressPercent = (
    (breathingDurationSeconds - remainingSeconds) / breathingDurationSeconds
  ) * 100
  // 호흡 단계 계산에 사용할 전체 경과 시간 계산.
  const elapsedSeconds = breathingDurationSeconds - remainingSeconds
  // 현재 경과 시간에 맞는 들이쉬기와 멈추기 및 내쉬기 단계 계산.
  const breathingPhase = getBreathingPhase(
    elapsedSeconds,
    remainingSeconds,
    isRunning,
  )
  // 현재 사용자가 진행 중인 호흡 회차 계산.
  const currentCycle = Math.floor(elapsedSeconds / breathingCycleSeconds) + 1
  // 호흡 안내 원의 실행과 일시정지 상태를 나타내는 클래스 이름 설정.
  const breathingCircleClassName = [
    'breathing-circle',
    breathingPhase.className,
    elapsedSeconds > 0 && remainingSeconds > 0 ? 'has-started' : '',
    isRunning ? 'is-running' : 'is-paused',
  ].filter(Boolean).join(' ')
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

  // 실제 종료 시각을 기준으로 남은 시간을 갱신하여 브라우저 지연에 따른 오차 방지.
  useEffect(() => {
    if (!isRunning || timerDeadlineRef.current === null) return undefined

    const updateRemainingTime = () => {
      const timerDeadline = timerDeadlineRef.current

      if (timerDeadline === null) return

      const nextRemainingSeconds = Math.max(
        0,
        Math.ceil((timerDeadline - Date.now()) / 1000),
      )

      setRemainingSeconds(nextRemainingSeconds)

      if (nextRemainingSeconds === 0) {
        timerDeadlineRef.current = null
        setIsRunning(false)
      }
    }

    updateRemainingTime()
    const timerId = window.setInterval(updateRemainingTime, 250)

    return () => window.clearInterval(timerId)
  }, [isRunning])

  // 시작과 일시정지 및 완료 후 재시작을 하나의 버튼으로 처리.
  const handleTimerToggle = () => {
    if (isRunning) {
      const pausedRemainingSeconds = timerDeadlineRef.current === null
        ? remainingSeconds
        : Math.max(
            0,
            Math.ceil((timerDeadlineRef.current - Date.now()) / 1000),
          )

      timerDeadlineRef.current = null
      setRemainingSeconds(pausedRemainingSeconds)
      setIsRunning(false)
      return
    }

    if (remainingSeconds === 0) {
      setRemainingSeconds(breathingDurationSeconds)
      timerDeadlineRef.current = Date.now() + breathingDurationSeconds * 1000
      setIsRunning(true)
      return
    }

    timerDeadlineRef.current = Date.now() + remainingSeconds * 1000
    setIsRunning(true)
  }

  // 진행 중인 호흡을 처음 상태로 되돌리는 처리.
  const handleTimerReset = () => {
    timerDeadlineRef.current = null
    setIsRunning(false)
    setRemainingSeconds(breathingDurationSeconds)
  }

  // 사용자가 현재 호흡을 직접 마치고 완료 상태를 확인하는 처리.
  const handleBreathingComplete = () => {
    timerDeadlineRef.current = null
    setIsRunning(false)
    setRemainingSeconds(0)
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

            {/* 현재 4초 들이쉬기와 4초 멈추기 및 6초 내쉬기 단계 표시. */}
            <div className="breathing-cycle">
              <div className={breathingCircleClassName}>
                <strong>{breathingPhase.name}</strong>
                {breathingPhase.remaining !== null && (
                  <span>{breathingPhase.remaining}초</span>
                )}
              </div>
              <p>4초 들이쉬기 · 4초 멈추기 · 6초 내쉬기</p>
            </div>

            {/* 단계 변화에 맞는 구체적인 호흡 방법과 현재 회차 표시. */}
            <div className="breathing-step-guide" role="status" aria-live="polite">
              <span>
                {elapsedSeconds > 0 && remainingSeconds > 0
                  ? `${currentCycle}회차 호흡`
                  : '호흡 안내'}
              </span>
              <p>{breathingPhase.instruction}</p>
            </div>

            <strong
              className="breathing-time"
              role="timer"
              aria-label={`남은 시간 ${formatRemainingTime(remainingSeconds)}`}
            >
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

            {/* 호흡 시작 후 처음부터 다시 진행하거나 현재 호흡을 마치는 기능 배치. */}
            {remainingSeconds > 0
              && remainingSeconds < breathingDurationSeconds && (
                <div className="breathing-secondary-actions">
                  <button type="button" onClick={handleTimerReset}>
                    처음부터
                  </button>
                  <button type="button" onClick={handleBreathingComplete}>
                    호흡 마치기
                  </button>
                </div>
              )}
          </section>

          {/* 3분 호흡 완료 후 다음 이동을 선택할 수 있는 안내 영역 표시. */}
          {remainingSeconds === 0 && (
            <section className="breathing-completion" aria-live="polite">
              <h2>호흡을 마쳤어요</h2>
              <p>잠시 편안해진 몸과 마음의 변화를 확인해 보세요.</p>
              <button type="button" onClick={onBack}>
                마음 돌봄 추천으로 돌아가기
              </button>
            </section>
          )}
        </section>
      </main>
    </div>
  )
}

export default Breathing
