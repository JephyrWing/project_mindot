import { useEffect, useRef, useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import './Meditation.css'

// 짧은 명상의 전체 진행 시간을 초 단위로 설정.
const meditationDurationSeconds = 60

// 일분 동안 순서대로 안내할 네 가지 명상 단계 설정.
const meditationSteps = [
  {
    title: '자세 가다듬기',
    instruction: '등과 어깨의 힘을 풀고 편안한 자세를 만들어 보세요.',
  },
  {
    title: '호흡 살펴보기',
    instruction: '숨이 들어오고 나가는 흐름을 바꾸지 않고 바라보세요.',
  },
  {
    title: '몸의 감각 느끼기',
    instruction: '발과 손이 닿는 감각을 천천히 알아차려 보세요.',
  },
  {
    title: '마음 바라보기',
    instruction: '떠오르는 생각을 판단하지 않고 잠시 흘려보내 보세요.',
  },
]

// 남은 초를 분과 초가 포함된 화면 표시 형식으로 변환.
const formatMeditationTime = (remainingSeconds) => {
  const minutes = Math.floor(remainingSeconds / 60)
  const seconds = String(remainingSeconds % 60).padStart(2, '0')

  return `${minutes}:${seconds}`
}

// 짧은 명상 기능을 단계적으로 확장하기 위한 전용 화면 기본 구조 정의.
function Meditation({
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
  // 사용자의 짧은 명상 시작 여부 상태 관리.
  const [isMeditating, setIsMeditating] = useState(false)
  // 화면에 표시할 남은 명상 시간 상태 관리.
  const [remainingSeconds, setRemainingSeconds] = useState(meditationDurationSeconds)
  // 현재 브라우저의 음성 합성 안내 지원 여부 확인.
  const supportsVoiceGuidance = typeof window !== 'undefined'
    && 'speechSynthesis' in window
    && 'SpeechSynthesisUtterance' in window
  // 사용자의 브라우저 음성 안내 사용 여부 상태 관리.
  const [isVoiceGuidanceEnabled, setIsVoiceGuidanceEnabled] = useState(
    supportsVoiceGuidance,
  )
  // 브라우저 화면 갱신 지연에도 실제 종료 시각을 유지하기 위한 기준 시각 저장.
  const meditationDeadlineRef = useRef(null)
  // 현재까지 진행한 명상 시간 계산.
  const elapsedSeconds = meditationDurationSeconds - remainingSeconds
  // 십오 초마다 변경되는 현재 명상 단계 순서 계산.
  const currentStepIndex = Math.min(
    meditationSteps.length - 1,
    Math.floor(elapsedSeconds / 15),
  )
  // 현재 시간에 해당하는 명상 안내 단계 선택.
  const currentStep = meditationSteps[currentStepIndex]
  // 전체 명상 시간에서 현재까지 진행한 비율 계산.
  const progressPercent = (
    (meditationDurationSeconds - remainingSeconds) / meditationDurationSeconds
  ) * 100
  // 남은 시간과 실행 상태에 맞는 화면 제목 설정.
  const guideTitle = remainingSeconds === 0
    ? '명상을 마쳤어요'
    : isMeditating
      ? currentStep.title
      : remainingSeconds < meditationDurationSeconds
        ? '잠시 멈춘 상태입니다'
        : '명상 준비'
  // 남은 시간과 실행 상태에 맞는 명상 안내 문구 설정.
  const guideDescription = remainingSeconds === 0
    ? '천천히 눈을 뜨고 명상 전과 달라진 몸과 마음을 확인해 보세요.'
    : isMeditating
      ? currentStep.instruction
      : remainingSeconds < meditationDurationSeconds
        ? '준비가 되면 남은 명상을 이어서 진행해 주세요.'
        : '편안하게 앉은 뒤 어깨의 힘을 풀고 자연스럽게 호흡해 주세요.'
  // 현재 상태에 맞는 명상 제어 버튼 이름 설정.
  const controlButtonLabel = isMeditating
    ? '잠시 멈추기'
    : remainingSeconds === 0
      ? '다시 시작하기'
      : remainingSeconds < meditationDurationSeconds
        ? '이어서 시작하기'
        : '명상 시작하기'

  // 실제 종료 시각을 기준으로 남은 명상 시간을 갱신하는 처리.
  useEffect(() => {
    if (!isMeditating || meditationDeadlineRef.current === null) return undefined

    const updateRemainingTime = () => {
      const meditationDeadline = meditationDeadlineRef.current

      if (meditationDeadline === null) return

      const nextRemainingSeconds = Math.max(
        0,
        Math.ceil((meditationDeadline - Date.now()) / 1000),
      )

      setRemainingSeconds(nextRemainingSeconds)

      if (nextRemainingSeconds === 0) {
        meditationDeadlineRef.current = null
        setIsMeditating(false)
      }
    }

    updateRemainingTime()
    const timerId = window.setInterval(updateRemainingTime, 250)

    return () => window.clearInterval(timerId)
  }, [isMeditating])

  // 현재 명상 단계가 바뀔 때 브라우저의 한국어 음성 안내 실행.
  useEffect(() => {
    if (!supportsVoiceGuidance || !isVoiceGuidanceEnabled || !isMeditating) {
      return undefined
    }

    window.speechSynthesis.cancel()

    const voiceGuide = new window.SpeechSynthesisUtterance(
      `${currentStep.title}. ${currentStep.instruction}`,
    )
    voiceGuide.lang = 'ko-KR'
    voiceGuide.rate = 0.9
    window.speechSynthesis.speak(voiceGuide)

    return () => window.speechSynthesis.cancel()
  }, [
    currentStep,
    isMeditating,
    isVoiceGuidanceEnabled,
    supportsVoiceGuidance,
  ])

  // 명상 시작과 일시정지 및 완료 후 재시작을 하나의 버튼으로 처리.
  const handleMeditationToggle = () => {
    if (isMeditating) {
      const pausedRemainingSeconds = meditationDeadlineRef.current === null
        ? remainingSeconds
        : Math.max(
            0,
            Math.ceil((meditationDeadlineRef.current - Date.now()) / 1000),
          )

      meditationDeadlineRef.current = null
      setRemainingSeconds(pausedRemainingSeconds)
      setIsMeditating(false)
      return
    }

    const nextDuration = remainingSeconds === 0
      ? meditationDurationSeconds
      : remainingSeconds

    if (remainingSeconds === 0) {
      setRemainingSeconds(meditationDurationSeconds)
    }

    meditationDeadlineRef.current = Date.now() + nextDuration * 1000
    setIsMeditating(true)
  }

  // 진행 중인 명상을 처음 상태로 되돌리는 처리.
  const handleMeditationReset = () => {
    meditationDeadlineRef.current = null
    setIsMeditating(false)
    setRemainingSeconds(meditationDurationSeconds)
  }

  // 사용자가 남은 시간과 관계없이 현재 명상을 완료하는 처리.
  const handleMeditationComplete = () => {
    meditationDeadlineRef.current = null
    setIsMeditating(false)
    setRemainingSeconds(0)
  }

  // 브라우저 음성 안내 사용 여부를 변경하는 처리.
  const handleVoiceGuidanceChange = (event) => {
    const nextEnabledState = event.target.checked

    if (!nextEnabledState && supportsVoiceGuidance) {
      window.speechSynthesis.cancel()
    }

    setIsVoiceGuidanceEnabled(nextEnabledState)
  }

  // 공통 네비게이션과 완성된 일분 명상 기능을 담은 화면 반환.
  return (
    <div className="meditation-page">
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

      <main className="meditation-main">
        {/* 마음 돌봄 추천 화면으로 돌아가는 이전 화면 이동 버튼 배치. */}
        <button className="meditation-back-button" type="button" onClick={onBack}>
          <span aria-hidden="true">←</span>
          뒤로 돌아가기
        </button>

        {/* 짧은 명상 전용 화면의 제목과 간단한 설명 배치. */}
        <section className="meditation-content" aria-labelledby="meditation-title">
          <h1 id="meditation-title">짧은 명상</h1>
          <p>잠시 호흡에 집중하며 지금의 마음을 살펴보는 공간입니다.</p>

          {/* 명상 전 준비 방법과 현재 진행 상태를 알려 주는 영역 배치. */}
          <section className="meditation-guide" aria-labelledby="meditation-guide-title">
            <h2 id="meditation-guide-title">{guideTitle}</h2>
            <p className="meditation-guide-description">{guideDescription}</p>

            {/* 현재 진행 중인 명상 단계와 전체 단계 수 표시. */}
            <div className="meditation-step-guide">
              <span>
                {remainingSeconds === 0
                  ? '4단계 완료'
                  : `${currentStepIndex + 1}/4 단계`}
              </span>
              <strong>{currentStep.title}</strong>
            </div>

            {/* 남은 명상 시간을 분과 초 형식으로 표시. */}
            <strong
              className="meditation-time"
              role="timer"
              aria-label={`남은 시간 ${formatMeditationTime(remainingSeconds)}`}
            >
              {formatMeditationTime(remainingSeconds)}
            </strong>

            {/* 전체 일분 중 현재까지 진행한 비율 표시. */}
            <div
              className="meditation-progress"
              role="progressbar"
              aria-label="짧은 명상 진행률"
              aria-valuemin="0"
              aria-valuemax="100"
              aria-valuenow={Math.round(progressPercent)}
            >
              <span style={{ width: `${progressPercent}%` }} />
            </div>

            {/* 브라우저 지원 여부에 따른 음성 명상 안내 선택 기능 배치. */}
            <label className="meditation-voice-option">
              <input
                type="checkbox"
                checked={isVoiceGuidanceEnabled}
                disabled={!supportsVoiceGuidance}
                onChange={handleVoiceGuidanceChange}
              />
              <span>음성 안내 사용</span>
            </label>
            {!supportsVoiceGuidance && (
              <p className="meditation-voice-notice">
                음성 안내를 지원하지 않는 브라우저에서는 화면 안내만 제공됩니다.
              </p>
            )}

            {/* 명상 시작 여부를 화면 읽기 도구에도 전달하는 상태 안내 배치. */}
            <p className="meditation-status" role="status" aria-live="polite">
              {remainingSeconds === 0
                ? '명상 완료'
                : isMeditating
                  ? '명상 진행 중'
                  : remainingSeconds < meditationDurationSeconds
                    ? '일시정지'
                    : '시작 전'}
            </p>

            <button
              className="meditation-control-button"
              type="button"
              onClick={handleMeditationToggle}
            >
              {controlButtonLabel}
            </button>

            {/* 명상 시작 후 처음부터 진행하거나 현재 명상을 마치는 기능 배치. */}
            {remainingSeconds > 0
              && remainingSeconds < meditationDurationSeconds && (
                <div className="meditation-secondary-actions">
                  <button type="button" onClick={handleMeditationReset}>
                    처음부터
                  </button>
                  <button type="button" onClick={handleMeditationComplete}>
                    명상 마치기
                  </button>
                </div>
              )}
          </section>

          {/* 명상 완료 후 마음 돌봄 추천 화면으로 돌아가는 안내 영역 표시. */}
          {remainingSeconds === 0 && (
            <section className="meditation-completion" aria-live="polite">
              <h2>잠시 마음을 살펴보세요</h2>
              <p>명상 전보다 호흡이나 몸의 긴장이 달라졌는지 확인해 보세요.</p>
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

export default Meditation
