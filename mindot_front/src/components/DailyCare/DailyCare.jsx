import { useEffect, useMemo, useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import {
  getEmotionRecordPatternExplanation,
  getEmotionRecords,
} from '../../utils/records/recordsApi.js'
import {
  getOpenReflectionSessions,
  getReflectionSessionDetail,
} from '../../utils/reflections/reflectionsApi.js'
import './DailyCare.css'

// 마음 돌봄 추천 만족도를 브라우저에 보관하기 위한 저장소 키 설정.
const dailyCareFeedbackStorageKey = 'mindot_daily_care_feedback'

// 사용자가 바로 따라 할 수 있는 짧은 명상 안내문 설정.
const meditationGuide = [
  '편안한 자세를 잡고 어깨의 힘을 천천히 풀어 주세요.',
  '숨이 들어오고 나가는 감각을 판단하지 않고 바라봐 주세요.',
  '떠오르는 생각이 있다면 알아차린 뒤 다시 호흡으로 돌아와 주세요.',
]

// 백엔드 감정 코드를 사용자에게 표시할 한국어 이름으로 변환하기 위한 목록 설정.
const emotionCodeLabels = {
  ANXIETY: '불안',
  FEAR: '두려움',
  ANGER: '분노',
  FRUSTRATION: '답답함',
  SADNESS: '슬픔',
  DISAPPOINTMENT: '실망',
  SHAME: '수치심',
  GUILT: '죄책감',
  LONELINESS: '외로움',
  JOY: '기쁨',
  RELIEF: '안도',
  ACHIEVEMENT: '성취감',
  CALM: '평온',
  GRATITUDE: '감사',
  EXCITEMENT: '설렘',
  OTHER: '복합적인 감정',
}

// 감정 기록과 진행 중 성찰 조회 오류를 사용자 안내 문구로 변환.
const getDailyCareErrorMessage = (error, fallbackMessage) => {
  if (!error?.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }

  return error.response.data?.message
    || error.response.data?.detail
    || fallbackMessage
}

// 패턴 설명 API 오류 상태를 현재 요청 조건에 맞는 안내 문구로 변환.
const getPatternErrorMessage = (error) => {
  if (error?.response?.status === 409) {
    return '패턴을 설명할 수 있는 완료된 CBT 기록이 아직 충분하지 않습니다.'
  }

  return getDailyCareErrorMessage(
    error,
    '감정 패턴을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
  )
}

// 감정 기록 시각을 사용자가 읽기 쉬운 한국어 형식으로 변환.
const formatRecordDate = (occurredAt) => {
  const recordDate = new Date(occurredAt)

  if (Number.isNaN(recordDate.getTime())) return '기록 시각 없음'

  return new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(recordDate)
}

// 남은 호흡 시간을 분과 초 형식으로 변환.
const formatRemainingTime = (remainingSeconds) => {
  const minutes = Math.floor(remainingSeconds / 60)
  const seconds = String(remainingSeconds % 60).padStart(2, '0')

  return `${minutes}:${seconds}`
}

// 현재 호흡 시간에 맞춰 들이마시기와 내쉬기 안내 문구 반환.
const getBreathingPhase = (remainingSeconds) => {
  if (remainingSeconds === 0) return '3분 호흡을 마쳤습니다.'

  const elapsedSeconds = 180 - remainingSeconds
  const phaseSeconds = elapsedSeconds % 10

  if (phaseSeconds < 4) return '코로 천천히 숨을 들이마셔요.'
  if (phaseSeconds < 6) return '잠시 편안하게 머물러요.'

  return '입으로 길게 숨을 내쉬어요.'
}

// 최신 감정 기록의 대표 감정과 강도에 맞는 기본 활동 제안 생성.
const createCareRecommendation = (latestRecord, hasOpenReflection) => {
  if (!latestRecord) {
    return {
      title: '오늘의 마음을 짧게 기록해 보세요.',
      description: '감정 기록이 쌓이면 현재 마음에 맞는 돌봄 활동을 안내해 드립니다.',
      activity: 'record',
    }
  }

  const emotionCode = latestRecord.primaryEmotionCode
  const intensity = Number(latestRecord.primaryIntensity)
  const anxiousEmotionCodes = ['ANXIETY', 'FEAR', 'ANGER', 'FRUSTRATION']
  const lowEnergyEmotionCodes = ['SADNESS', 'DISAPPOINTMENT', 'LONELINESS', 'GUILT']

  if (hasOpenReflection) {
    return {
      title: '멈춰 둔 CBT 성찰을 이어가 보세요.',
      description: '최근 감정에서 시작한 대화를 이어서 생각을 차분하게 정리할 수 있습니다.',
      activity: 'cbt',
    }
  }

  if (intensity >= 4 || anxiousEmotionCodes.includes(emotionCode)) {
    return {
      title: '3분 호흡으로 긴장을 천천히 낮춰 보세요.',
      description: '강하게 느껴지는 감정에서 잠시 거리를 둘 수 있도록 호흡을 안내해 드립니다.',
      activity: 'breathing',
    }
  }

  if (lowEnergyEmotionCodes.includes(emotionCode)) {
    return {
      title: '짧은 명상으로 지금의 마음을 살펴보세요.',
      description: '마음을 바꾸려 애쓰기보다 현재의 감각과 생각을 차분히 바라보는 시간입니다.',
      activity: 'meditation',
    }
  }

  return {
    title: '최근 감정을 CBT 성찰로 조금 더 살펴보세요.',
    description: '기록한 생각을 바탕으로 새로운 관점을 찾는 대화를 시작할 수 있습니다.',
    activity: 'cbt',
  }
}

// 실제 감정 기록과 CBT 데이터를 바탕으로 마음 돌봄 활동을 제안하는 화면 정의.
function DailyCare({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onDailyCare,
  onHome,
  onEmotionRecord,
  onCBT,
  onReflectionResume,
}) {
  // 백엔드에서 조회한 로그인 사용자의 감정 기록 목록 상태 설정.
  const [emotionRecords, setEmotionRecords] = useState([])
  // 백엔드에서 조회한 진행 중 CBT 성찰 목록 상태 설정.
  const [openReflections, setOpenReflections] = useState([])
  // 마음 돌봄 기초 데이터 조회 진행 여부 상태 설정.
  const [isLoading, setIsLoading] = useState(true)
  // 마음 돌봄 기초 데이터 조회 실패 안내 상태 설정.
  const [loadError, setLoadError] = useState('')
  // 사용자의 기초 데이터 재조회 요청 횟수 상태 설정.
  const [reloadCount, setReloadCount] = useState(0)
  // 최신 감정 기록을 기반으로 생성한 패턴 설명 상태 설정.
  const [patternExplanation, setPatternExplanation] = useState(null)
  // 패턴 설명 요청 진행 여부 상태 설정.
  const [isLoadingPattern, setIsLoadingPattern] = useState(false)
  // 패턴 설명 요청 실패 안내 상태 설정.
  const [patternError, setPatternError] = useState('')
  // 진행 중 CBT 상세 조회와 화면 이동 진행 여부 상태 설정.
  const [isOpeningCbt, setIsOpeningCbt] = useState(false)
  // CBT 시작 또는 이어하기 실패 안내 상태 설정.
  const [cbtError, setCbtError] = useState('')
  // 사용자가 현재 펼쳐 본 호흡 또는 명상 활동 상태 설정.
  const [activeActivity, setActiveActivity] = useState('')
  // 3분 호흡 활동의 남은 초 상태 설정.
  const [remainingBreathingSeconds, setRemainingBreathingSeconds] = useState(180)
  // 3분 호흡 타이머 동작 여부 상태 설정.
  const [isBreathingActive, setIsBreathingActive] = useState(false)
  // 브라우저 명상 음성 안내 재생 여부 상태 설정.
  const [isMeditationPlaying, setIsMeditationPlaying] = useState(false)
  // 브라우저 명상 음성 안내 상태 문구 설정.
  const [meditationStatus, setMeditationStatus] = useState('')
  // 사용자가 선택한 추천 만족도 상태 설정.
  const [selectedFeedback, setSelectedFeedback] = useState('')
  // 화면 진입 시점을 기준으로 최근 칠 일 범위를 일정하게 유지하는 기준 시각 설정.
  const [recentPeriodStartTime] = useState(
    () => Date.now() - (7 * 24 * 60 * 60 * 1000),
  )

  // 화면 진입과 재조회 요청 시 감정 기록과 진행 중 CBT 목록 병렬 조회.
  useEffect(() => {
    let isActive = true

    const loadDailyCareData = async () => {
      setIsLoading(true)
      setLoadError('')
      setPatternExplanation(null)
      setPatternError('')

      const [recordsResult, reflectionsResult] = await Promise.allSettled([
        getEmotionRecords(),
        getOpenReflectionSessions(),
      ])

      if (!isActive) return

      if (recordsResult.status === 'fulfilled') {
        setEmotionRecords(Array.isArray(recordsResult.value) ? recordsResult.value : [])
      } else {
        setEmotionRecords([])
      }

      if (reflectionsResult.status === 'fulfilled') {
        setOpenReflections(
          Array.isArray(reflectionsResult.value) ? reflectionsResult.value : [],
        )
      } else {
        setOpenReflections([])
      }

      const failedResult = recordsResult.status === 'rejected'
        ? recordsResult
        : reflectionsResult.status === 'rejected'
          ? reflectionsResult
          : null

      if (failedResult) {
        setLoadError(getDailyCareErrorMessage(
          failedResult.reason,
          '마음 돌봄 추천에 필요한 기록을 모두 불러오지 못했습니다.',
        ))
      }

      setIsLoading(false)
    }

    loadDailyCareData()

    return () => {
      isActive = false
    }
  }, [reloadCount])

  // 호흡 타이머 실행 중 다음 일 초의 감소 작업만 예약하는 처리.
  useEffect(() => {
    if (!isBreathingActive || remainingBreathingSeconds === 0) return undefined

    const timerId = window.setTimeout(() => {
      setRemainingBreathingSeconds((currentSeconds) => Math.max(currentSeconds - 1, 0))
    }, 1000)

    return () => window.clearTimeout(timerId)
  }, [isBreathingActive, remainingBreathingSeconds])

  // 화면 이탈 시 현재 브라우저 음성 안내를 정리하는 처리.
  useEffect(() => () => {
    window.speechSynthesis?.cancel()
  }, [])

  // 응답 목록을 감정 발생 시각 기준 최신순으로 정렬한 결과 생성.
  const sortedEmotionRecords = useMemo(() => [...emotionRecords].sort(
    (firstRecord, secondRecord) => (
      new Date(secondRecord.occurredAt).getTime()
      - new Date(firstRecord.occurredAt).getTime()
    ),
  ), [emotionRecords])

  // 감정 기록 중 현재 마음 돌봄 기준으로 사용할 최신 기록 탐색.
  const latestRecord = sortedEmotionRecords[0] ?? null

  // 최근 칠 일 이내에 작성된 실제 감정 기록 목록 계산.
  const recentSevenDayRecords = sortedEmotionRecords.filter((record) => (
    new Date(record.occurredAt).getTime() >= recentPeriodStartTime
  ))

  // OPEN CBT 목록 중 가장 최근에 생성된 세션 탐색.
  const latestOpenReflection = useMemo(() => [...openReflections].sort(
    (firstSession, secondSession) => (
      new Date(secondSession.createdAt).getTime()
      - new Date(firstSession.createdAt).getTime()
    ),
  )[0] ?? null, [openReflections])

  // 최신 감정과 진행 중 CBT 여부를 반영한 화면 추천 정보 생성.
  const careRecommendation = createCareRecommendation(
    latestRecord,
    Boolean(latestOpenReflection),
  )

  // 최신 감정 기록에서 대표 감정 표시 문구 탐색.
  const latestEmotionLabel = latestRecord
    ? emotionCodeLabels[latestRecord.primaryEmotionCode] ?? '분석 전 감정'
    : '기록 없음'

  // 최신 확정 감정 기록을 사용한 AI 패턴 설명 요청 처리.
  const handlePatternExplanation = async () => {
    if (!latestRecord || isLoadingPattern) return

    setIsLoadingPattern(true)
    setPatternError('')

    try {
      const explanation = await getEmotionRecordPatternExplanation(
        latestRecord.emotionRecordId,
      )

      setPatternExplanation(explanation)
    } catch (error) {
      setPatternExplanation(null)
      setPatternError(getPatternErrorMessage(error))
    } finally {
      setIsLoadingPattern(false)
    }
  }

  // 진행 중 세션이 있으면 상세 조회 후 이어가고 없으면 최신 기록으로 새 CBT 시작 처리.
  const handleCbtAction = async () => {
    if (isOpeningCbt) return

    setCbtError('')

    if (!latestOpenReflection) {
      if (latestRecord) {
        onCBT(latestRecord.emotionRecordId)
      } else {
        setCbtError('먼저 감정을 기록한 뒤 CBT 성찰을 시작해 주세요.')
      }
      return
    }

    setIsOpeningCbt(true)

    try {
      const reflectionDetail = await getReflectionSessionDetail(
        latestOpenReflection.sessionId,
      )

      onReflectionResume({
        ...reflectionDetail,
        emotionRecordId: latestOpenReflection.emotionRecordId,
      })
    } catch (error) {
      setCbtError(getDailyCareErrorMessage(
        error,
        '진행 중인 CBT 성찰을 불러오지 못했습니다.',
      ))
    } finally {
      setIsOpeningCbt(false)
    }
  }

  // 선택한 마음 돌봄 활동을 펼치고 다른 음성 안내를 정리하는 처리.
  const handleActivityOpen = (activityName) => {
    if (activityName !== 'meditation') {
      window.speechSynthesis?.cancel()
      setIsMeditationPlaying(false)
      setMeditationStatus('')
    }

    setActiveActivity(activityName)
  }

  // 3분 호흡 타이머 시작과 일시 정지 및 완료 후 재시작 처리.
  const handleBreathingToggle = () => {
    setActiveActivity('breathing')

    if (remainingBreathingSeconds === 0) {
      setRemainingBreathingSeconds(180)
      setIsBreathingActive(true)
      return
    }

    setIsBreathingActive((currentState) => !currentState)
  }

  // 3분 호흡 타이머를 처음 상태로 되돌리는 처리.
  const handleBreathingReset = () => {
    setRemainingBreathingSeconds(180)
    setIsBreathingActive(false)
  }

  // 브라우저 음성 합성 기능을 이용한 짧은 명상 안내 시작과 중단 처리.
  const handleMeditationPlayback = () => {
    setActiveActivity('meditation')

    if (!('speechSynthesis' in window) || !('SpeechSynthesisUtterance' in window)) {
      setMeditationStatus('이 브라우저에서는 음성 안내를 지원하지 않습니다. 화면의 안내문을 따라 해 주세요.')
      return
    }

    if (isMeditationPlaying) {
      window.speechSynthesis.cancel()
      setIsMeditationPlaying(false)
      setMeditationStatus('음성 안내를 중단했습니다.')
      return
    }

    const utterance = new window.SpeechSynthesisUtterance(meditationGuide.join(' '))

    utterance.lang = 'ko-KR'
    utterance.rate = 0.85
    utterance.onend = () => {
      setIsMeditationPlaying(false)
      setMeditationStatus('짧은 명상 안내를 마쳤습니다.')
    }
    utterance.onerror = () => {
      setIsMeditationPlaying(false)
      setMeditationStatus('음성 안내를 재생하지 못했습니다. 안내문을 따라 천천히 진행해 주세요.')
    }

    window.speechSynthesis.cancel()
    window.speechSynthesis.speak(utterance)
    setIsMeditationPlaying(true)
    setMeditationStatus('짧은 명상 안내를 재생하고 있습니다.')
  }

  // 추천 만족도를 브라우저에 보관하고 현재 선택 상태를 갱신하는 처리.
  const handleFeedbackSave = (feedbackValue) => {
    const feedback = {
      value: feedbackValue,
      emotionRecordId: latestRecord?.emotionRecordId ?? null,
      savedAt: new Date().toISOString(),
    }

    try {
      window.localStorage.setItem(
        dailyCareFeedbackStorageKey,
        JSON.stringify(feedback),
      )
      setSelectedFeedback(feedbackValue)
    } catch {
      setSelectedFeedback('storage-error')
    }
  }

  // 실제 감정 기록과 마음 돌봄 실행 도구로 구성한 화면 반환.
  return (
    <div className="daily-care-page">
      {/* 다른 서비스 화면과 동일한 크기와 기능의 공통 네비게이션 배치. */}
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

      <main className="daily-care-main">
        {/* 실제 감정 기록을 기준으로 화면 목적과 추천 근거를 설명하는 도입 영역. */}
        <section className="daily-care-intro" aria-labelledby="daily-care-title">
          <h1 id="daily-care-title">오늘의 마음 돌봄 추천</h1>
          <p>
            {isLoading
              ? '최근 감정 기록을 확인하고 있습니다.'
              : latestRecord
                ? `${formatRecordDate(latestRecord.occurredAt)}에 남긴 기록을 기준으로 안내합니다.`
                : '아직 감정 기록이 없어 기본 마음 돌봄 활동을 안내합니다.'}
          </p>
          <dl className="daily-care-basis">
            <div>
              <dt>최근 7일 기록</dt>
              <dd>{isLoading ? '-' : `${recentSevenDayRecords.length}개`}</dd>
            </div>
            <div>
              <dt>최근 감정</dt>
              <dd>{isLoading ? '-' : latestEmotionLabel}</dd>
            </div>
            <div>
              <dt>감정 강도</dt>
              <dd>
                {isLoading
                  ? '-'
                  : Number.isFinite(latestRecord?.primaryIntensity)
                    ? `${latestRecord.primaryIntensity}/5`
                    : '분석 전'}
              </dd>
            </div>
          </dl>
        </section>

        {loadError && (
          <div className="daily-care-load-error" role="alert">
            <p>{loadError}</p>
            <button
              type="button"
              onClick={() => setReloadCount((currentCount) => currentCount + 1)}
            >
              다시 불러오기
            </button>
          </div>
        )}

        {/* 최신 감정과 진행 중 CBT 상태를 반영한 우선 추천 영역. */}
        <section className="daily-care-suggestion" aria-labelledby="daily-care-suggestion-title">
          <h2 id="daily-care-suggestion-title">오늘의 제안</h2>
          <strong>{careRecommendation.title}</strong>
          <p>{careRecommendation.description}</p>
        </section>

        {/* 확정 감정 기록과 완료 CBT 사례를 활용하는 AI 패턴 설명 영역. */}
        <section className="daily-care-pattern" aria-labelledby="daily-care-pattern-title">
          <div className="daily-care-section-heading">
            <div>
              <h2 id="daily-care-pattern-title">내 감정 패턴 살펴보기</h2>
              <p>최신 기록과 유사한 완료 CBT 사례를 바탕으로 설명합니다.</p>
            </div>
            <button
              type="button"
              onClick={handlePatternExplanation}
              disabled={!latestRecord || isLoadingPattern}
            >
              {isLoadingPattern ? '분석 중' : '패턴 설명 요청'}
            </button>
          </div>

          {patternError && <p className="daily-care-message is-error" role="alert">{patternError}</p>}

          {patternExplanation && (
            <div className="daily-care-pattern-result" aria-live="polite">
              <h3>패턴 요약</h3>
              <p>{patternExplanation.patternSummary}</p>
              {patternExplanation.helpfulAlternativeThought && (
                <>
                  <h3>도움이 된 다른 생각</h3>
                  <p>{patternExplanation.helpfulAlternativeThought}</p>
                </>
              )}
              {patternExplanation.recommendation && (
                <>
                  <h3>추천 활동</h3>
                  <p>{patternExplanation.recommendation}</p>
                </>
              )}
              <small>유사한 완료 CBT {patternExplanation.similarCaseCount}건 기준</small>
            </div>
          )}
        </section>

        {/* 호흡과 명상 및 실제 CBT 이동 기능을 제공하는 마음 돌봄 활동 목록. */}
        <section className="daily-care-actions" aria-label="마음 돌봄 활동">
          <article className={careRecommendation.activity === 'breathing' ? 'daily-care-action is-recommended' : 'daily-care-action'}>
            <div>
              <h2>3분 호흡</h2>
              <p>화면의 호흡 안내와 타이머를 따라 긴장을 천천히 낮춰요.</p>
            </div>
            <button
              className="daily-care-primary-button"
              type="button"
              onClick={() => handleActivityOpen('breathing')}
            >
              시작하기
            </button>
          </article>

          <article className={careRecommendation.activity === 'meditation' ? 'daily-care-action is-recommended' : 'daily-care-action'}>
            <div>
              <h2>짧은 명상</h2>
              <p>브라우저 음성 안내와 함께 현재의 감각을 차분히 살펴봐요.</p>
            </div>
            <button type="button" onClick={() => handleActivityOpen('meditation')}>
              명상 열기
            </button>
          </article>

          <article className={careRecommendation.activity === 'cbt' ? 'daily-care-action is-recommended' : 'daily-care-action'}>
            <div>
              <h2>{latestOpenReflection ? 'CBT 성찰 이어하기' : 'CBT 성찰 시작하기'}</h2>
              <p>
                {latestOpenReflection
                  ? '진행 중인 대화 이력을 불러와 멈춘 지점부터 이어가요.'
                  : '최신 감정 기록을 바탕으로 새로운 관점을 찾아봐요.'}
              </p>
            </div>
            <button
              type="button"
              onClick={handleCbtAction}
              disabled={isOpeningCbt}
            >
              {isOpeningCbt
                ? '불러오는 중'
                : latestOpenReflection
                  ? '이어서 하기'
                  : '시작하기'}
            </button>
          </article>
          {cbtError && <p className="daily-care-message is-error" role="alert">{cbtError}</p>}
        </section>

        {/* 사용자가 선택한 3분 호흡 활동의 타이머와 단계 안내 영역. */}
        {activeActivity === 'breathing' && (
          <section className="daily-care-tool" aria-labelledby="daily-care-breathing-title">
            <h2 id="daily-care-breathing-title">3분 호흡 안내</h2>
            <strong className="daily-care-timer" aria-live="polite">
              {formatRemainingTime(remainingBreathingSeconds)}
            </strong>
            <p>{getBreathingPhase(remainingBreathingSeconds)}</p>
            <div className="daily-care-tool-actions">
              <button type="button" onClick={handleBreathingToggle}>
                {remainingBreathingSeconds === 0
                  ? '다시 시작'
                  : isBreathingActive
                    ? '일시 정지'
                    : '호흡 시작'}
              </button>
              <button type="button" onClick={handleBreathingReset}>처음부터</button>
            </div>
          </section>
        )}

        {/* 사용자가 선택한 짧은 명상 활동의 안내문과 음성 재생 영역. */}
        {activeActivity === 'meditation' && (
          <section className="daily-care-tool" aria-labelledby="daily-care-meditation-title">
            <h2 id="daily-care-meditation-title">짧은 명상 안내</h2>
            <ol className="daily-care-meditation-steps">
              {meditationGuide.map((guide) => <li key={guide}>{guide}</li>)}
            </ol>
            <button
              className="daily-care-tool-wide-button"
              type="button"
              onClick={handleMeditationPlayback}
            >
              {isMeditationPlaying ? '음성 안내 중단' : '음성 안내 듣기'}
            </button>
            {meditationStatus && <p className="daily-care-message" aria-live="polite">{meditationStatus}</p>}
          </section>
        )}

        {!latestRecord && !isLoading && (
          <button className="daily-care-record-button" type="button" onClick={onEmotionRecord}>
            첫 감정 기록하기
          </button>
        )}

        {/* 추천 만족도를 API 추가 전까지 브라우저에 보관하는 선택 영역. */}
        <section className="daily-care-feedback" aria-labelledby="daily-care-feedback-title">
          <h2 id="daily-care-feedback-title">오늘의 제안이 도움이 되었나요?</h2>
          <p>현재 선택 결과는 이 브라우저에만 저장됩니다.</p>
          <div className="daily-care-feedback-actions">
            <button
              className={selectedFeedback === 'helpful' ? 'is-selected' : ''}
              type="button"
              onClick={() => handleFeedbackSave('helpful')}
              aria-pressed={selectedFeedback === 'helpful'}
            >
              도움됐어요
            </button>
            <button
              className={selectedFeedback === 'later' ? 'is-selected' : ''}
              type="button"
              onClick={() => handleFeedbackSave('later')}
              aria-pressed={selectedFeedback === 'later'}
            >
              다음에 추천해요
            </button>
          </div>
          {selectedFeedback && selectedFeedback !== 'storage-error' && (
            <p className="daily-care-message is-success" aria-live="polite">
              선택한 의견을 저장했습니다.
            </p>
          )}
          {selectedFeedback === 'storage-error' && (
            <p className="daily-care-message is-error" role="alert">
              브라우저에 의견을 저장하지 못했습니다.
            </p>
          )}
        </section>
      </main>
    </div>
  )
}

export default DailyCare
