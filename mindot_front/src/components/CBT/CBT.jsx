import { useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import SafetyNoticeModal from '../SafetyNoticeModal/SafetyNoticeModal.jsx'
import {
  cancelReflection,
  confirmReflection,
  startReflection,
  submitReflectionAnswer,
} from '../../utils/reflections/reflectionsApi.js'
import {
  confirmEmotionRecord,
  getEmotionRecordDetail,
} from '../../utils/records/recordsApi.js'
import './CBT.css'

// CBT 답변의 백엔드 최대 허용 글자 수 설정.
const maxAnswerLength = 4000

// 백엔드 인지왜곡 코드를 사용자에게 표시할 한국어 이름으로 변환하기 위한 목록 설정.
const distortionCodeLabels = {
  ALL_OR_NOTHING_THINKING: '흑백논리',
  CATASTROPHIZING_FORTUNE_TELLING: '파국화·미래예측',
  DISQUALIFYING_DISCOUNTING_POSITIVE: '긍정적인 면 무시',
  EMOTIONAL_REASONING: '감정적 추론',
  LABELING: '낙인찍기',
  MAGNIFICATION_MINIMIZATION: '과장·축소',
  MENTAL_FILTER_SELECTIVE_ABSTRACTION: '정신적 여과',
  MIND_READING: '독심술',
  OVERGENERALIZATION: '과잉일반화',
  PERSONALIZATION: '개인화',
  SHOULD_MUST_STATEMENTS: '당위적 사고',
  TUNNEL_VISION: '터널 시야',
}

// CBT 최종 결과 입력값의 초기 상태 설정.
const initialConfirmationForm = {
  evidenceForText: '',
  evidenceAgainstText: '',
  alternativeThoughtText: '',
  beforeBeliefStrength: '',
  afterBeliefStrength: '',
  finalEmotionIntensity: '',
  helpfulnessScore: '',
}

// CBT API 오류 응답을 사용자가 이해할 수 있는 문구로 변환.
const getReflectionErrorMessage = (error, fallbackMessage) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }

  return error.response.data?.message
    || error.response.data?.detail
    || fallbackMessage
}

// CBT 최종 확정 API 오류 상태에 따른 사용자 안내 문구 반환.
const getConfirmationErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 400) {
    return '입력한 성찰 결과를 다시 확인해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (error.response.status === 404) {
    return '확정할 CBT 성찰 세션을 찾을 수 없습니다.'
  }
  if (error.response.status === 409) {
    return '이미 완료되었거나 아직 확정할 수 없는 CBT 성찰입니다.'
  }

  return error.response.data?.message
    || error.response.data?.detail
    || 'CBT 성찰 결과를 확정하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// CBT API 응답 상태에 맞는 AI 안내 문구 반환.
const getResponseMessage = (response) => {
  if (response.safetyNotice?.actionCode === 'SHOW_CRISIS_NOTICE') {
    return '안전을 위해 CBT 답변 입력을 중단했습니다. 안전 안내의 연락 수단을 먼저 확인해 주세요.'
  }
  if (response.nextQuestion?.question) {
    return response.nextQuestion.question
  }
  if (response.proposalMessage) {
    return response.proposalMessage
  }
  if (
    response.status === 'CONFIRM_REQUIRED'
    && response.assessmentType === 'NO_CLEAR_DISTORTION'
  ) {
    return '대화를 살펴본 결과, 현재 생각에서 명확한 인지왜곡은 확인되지 않았습니다.'
  }
  if (response.status === 'CONFIRM_REQUIRED') {
    return '대화를 바탕으로 성찰 결과가 준비되었습니다.'
  }
  if (response.status === 'SAFETY_STOP') {
    return '안전을 위해 CBT 대화를 잠시 중단합니다. 즉각적인 도움이 필요하면 112 또는 119에 연락해 주세요.'
  }

  return 'CBT 성찰 대화가 마무리되었습니다.'
}

// 저장된 질문과 답변 배열을 CBT 대화창에서 사용할 순서형 메시지 목록으로 변환.
const createResumedChatMessages = (resumeSession) => (
  (resumeSession?.questionAnswers ?? []).flatMap((questionAnswer, index) => {
    const messages = [{
      id: `ai-${resumeSession.sessionId}-history-${index}`,
      sender: 'ai',
      text: questionAnswer.question || '질문 내용이 없습니다.',
    }]

    if (questionAnswer.answer?.trim()) {
      messages.push({
        id: `user-${resumeSession.sessionId}-history-${index}`,
        sender: 'user',
        text: questionAnswer.answer,
      })
    }

    return messages
  })
)

// 감정 기록을 바탕으로 생각을 돌아보는 기본 CBT 성찰 화면 컴포넌트 정의.
function CBT({
  emotionRecordId,
  resumeSession,
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onDailyCare,
  onHome,
}) {
  // CBT AI 대화창 시작 여부 상태 관리.
  const [isChatStarted, setIsChatStarted] = useState(
    () => Boolean(resumeSession?.sessionId),
  )
  // 사용자가 작성 중인 답변 내용 상태 관리.
  const [message, setMessage] = useState('')
  // API에서 주고받은 AI 질문과 사용자 답변 목록 상태 관리.
  const [chatMessages, setChatMessages] = useState(
    () => createResumedChatMessages(resumeSession),
  )
  // 백엔드가 생성한 CBT 성찰 세션 식별자 상태 관리.
  const [sessionId, setSessionId] = useState(
    () => resumeSession?.sessionId ?? null,
  )
  // CBT 세션 시작 요청 진행 여부 상태 관리.
  const [isStarting, setIsStarting] = useState(false)
  // CBT 답변 전송 요청 진행 여부 상태 관리.
  const [isSending, setIsSending] = useState(false)
  // CBT API 요청 실패 안내 문구 상태 관리.
  const [apiError, setApiError] = useState('')
  // CBT 시작 전 자동 사고 보완이 필요한 감정 기록 상세 정보 상태 관리.
  const [recordForCbt, setRecordForCbt] = useState(null)
  // CBT 시작에 필요한 사용자의 자동 사고 입력값 상태 관리.
  const [automaticThought, setAutomaticThought] = useState('')
  // 자동 사고 보완 입력 영역 표시 여부 상태 관리.
  const [isAutomaticThoughtRequired, setIsAutomaticThoughtRequired] = useState(false)
  // CBT 대화 계속 여부를 판단하기 위한 백엔드 응답 상태 관리.
  const [reflectionStatus, setReflectionStatus] = useState(
    () => (resumeSession?.sessionId ? 'CONTINUE' : 'IDLE'),
  )
  // CONFIRM_REQUIRED 결과의 인지왜곡 판정 유형 상태 관리.
  const [assessmentType, setAssessmentType] = useState('')
  // AI가 제안한 CBT 최종 결과와 사용자의 수정값 상태 관리.
  const [confirmationForm, setConfirmationForm] = useState(initialConfirmationForm)
  // 성찰 전 AI 제안 인지왜곡의 사용자 검토 상태 관리.
  const [beforeDistortions, setBeforeDistortions] = useState([])
  // 성찰 후 AI 제안 인지왜곡의 사용자 검토 상태 관리.
  const [afterDistortions, setAfterDistortions] = useState([])
  // CBT 최종 결과 확정 요청 진행 여부 상태 관리.
  const [isConfirming, setIsConfirming] = useState(false)
  // CBT 최종 결과 확정 완료 여부 상태 관리.
  const [isConfirmed, setIsConfirmed] = useState(false)
  // CBT 최종 결과 검증 또는 API 오류 안내 문구 상태 관리.
  const [confirmationError, setConfirmationError] = useState('')
  // CBT 성찰 세션 취소 요청 진행 여부 상태 관리.
  const [isCancelling, setIsCancelling] = useState(false)
  // CBT 성찰 세션 이동 또는 취소 오류 안내 문구 상태 관리.
  const [sessionActionError, setSessionActionError] = useState('')
  // CBT 시작 또는 답변 응답에서 반환된 안전 안내 모달 정보 상태 관리.
  const [safetyNotice, setSafetyNotice] = useState(null)
  // 위기 안전 안내 이후 CBT 답변 입력을 계속 차단하기 위한 상태 관리.
  const [isCrisisBlocked, setIsCrisisBlocked] = useState(false)

  // CBT 응답의 안전 안내를 반영하고 위기 입력 차단 필요 여부 반환.
  const applySafetyNotice = (response) => {
    const responseSafetyNotice = response.safetyNotice

    if (!responseSafetyNotice) return false

    const shouldBlockCbt = responseSafetyNotice.actionCode
      === 'SHOW_CRISIS_NOTICE'

    setSafetyNotice(responseSafetyNotice)
    if (shouldBlockCbt) setIsCrisisBlocked(true)

    return shouldBlockCbt
  }

  // AI의 최종 결과 초안과 인지왜곡 제안을 사용자 검토 입력값으로 변환하는 처리.
  const prepareConfirmationForm = (response) => {
    if (response.status !== 'CONFIRM_REQUIRED') {
      setAssessmentType('')
      return
    }

    const outcomeDraft = response.outcomeDraft ?? {}

    setAssessmentType(response.assessmentType ?? 'DISTORTION_PRESENT')
    setConfirmationForm({
      ...initialConfirmationForm,
      evidenceForText: outcomeDraft.evidenceForText ?? '',
      evidenceAgainstText: outcomeDraft.evidenceAgainstText ?? '',
      alternativeThoughtText: outcomeDraft.alternativeThoughtText ?? '',
    })
    setBeforeDistortions((response.beforeDistortions ?? []).map((distortion) => ({
      code: distortion.code,
      reviewStatus: '',
    })))
    setAfterDistortions((outcomeDraft.afterDistortions ?? []).map((distortion) => ({
      code: distortion.code,
      reviewStatus: '',
    })))
    setConfirmationError('')
    setIsConfirmed(false)
  }

  // CBT 세션 시작 응답을 현재 대화 화면 상태에 반영하는 처리.
  const applyReflectionStartResponse = (response) => {
    const shouldBlockCbt = applySafetyNotice(response)

    setSessionId(response.sessionId)
    setReflectionStatus(shouldBlockCbt ? 'SAFETY_STOP' : response.status)
    if (shouldBlockCbt) {
      setAssessmentType('')
    } else {
      prepareConfirmationForm(response)
    }
    setChatMessages([{
      id: `ai-${response.sessionId}-start`,
      sender: 'ai',
      text: getResponseMessage(response),
    }])
    setIsChatStarted(true)
    setIsAutomaticThoughtRequired(false)
  }

  // 저장된 감정 기록을 사용하여 첫 CBT 질문을 요청하는 처리.
  const handleChatStart = async () => {
    if (!emotionRecordId || isStarting) return

    setIsStarting(true)
    setApiError('')

    try {
      // CBT 시작에 필요한 자동 사고가 기록되어 있는지 상세 API로 사전 확인.
      const emotionRecord = await getEmotionRecordDetail(emotionRecordId)

      if (!emotionRecord.automaticThought?.trim()) {
        setRecordForCbt(emotionRecord)
        setAutomaticThought('')
        setIsAutomaticThoughtRequired(true)
        return
      }

      const response = await startReflection(emotionRecordId)

      applyReflectionStartResponse(response)
    } catch (error) {
      setApiError(getReflectionErrorMessage(
        error,
        'CBT 대화를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      ))
    } finally {
      setIsStarting(false)
    }
  }

  // 사용자가 입력한 자동 사고를 감정 기록에 반영한 뒤 CBT 세션 시작 요청.
  const handleAutomaticThoughtSubmit = async (event) => {
    event.preventDefault()

    const trimmedAutomaticThought = automaticThought.trim()

    if (!trimmedAutomaticThought || !recordForCbt || isStarting) return

    setIsStarting(true)
    setApiError('')

    try {
      await confirmEmotionRecord(emotionRecordId, {
        situationText: recordForCbt.situationText,
        automaticThought: trimmedAutomaticThought,
        primaryEmotionCode: recordForCbt.primaryEmotionCode,
        primaryIntensity: recordForCbt.primaryIntensity,
        secondaryEmotions: recordForCbt.secondaryEmotions ?? [],
        contextCategory: recordForCbt.contextCategory,
        relatedPersonType: recordForCbt.relatedPersonType,
        details: recordForCbt.details ?? {},
      })

      const response = await startReflection(emotionRecordId)

      applyReflectionStartResponse(response)
    } catch (error) {
      setApiError(getReflectionErrorMessage(
        error,
        '자동 사고를 저장하거나 CBT 대화를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      ))
    } finally {
      setIsStarting(false)
    }
  }

  // 현재 CBT 질문의 답변을 백엔드에 전달하고 다음 AI 질문을 표시하는 처리.
  const handleMessageSubmit = async (event) => {
    event.preventDefault()

    const trimmedMessage = message.trim()
    if (!trimmedMessage || !sessionId || isSending || isCrisisBlocked) return

    setIsSending(true)
    setApiError('')

    try {
      const response = await submitReflectionAnswer(sessionId, trimmedMessage)
      const shouldBlockCbt = applySafetyNotice(response)

      if (shouldBlockCbt) {
        // 위기 신호 감지 시 기존 대화 대신 안전 안내 메시지만 유지하는 처리.
        setChatMessages([{
          id: `ai-${sessionId}-safety`,
          sender: 'ai',
          text: getResponseMessage(response),
        }])
        setReflectionStatus('SAFETY_STOP')
        setAssessmentType('')
      } else {
        setChatMessages((currentMessages) => [
          ...currentMessages,
          {
            id: `user-${sessionId}-${currentMessages.length}`,
            sender: 'user',
            text: trimmedMessage,
          },
          {
            id: `ai-${sessionId}-${currentMessages.length + 1}`,
            sender: 'ai',
            text: getResponseMessage(response),
          },
        ])
        setReflectionStatus(response.status)
        prepareConfirmationForm(response)
      }
      setMessage('')
    } catch (error) {
      setApiError(getReflectionErrorMessage(
        error,
        '답변을 전송하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      ))
    } finally {
      setIsSending(false)
    }
  }

  // CBT 최종 결과 입력값 변경과 기존 오류 안내 초기화 처리.
  const handleConfirmationChange = (event) => {
    const { name, value } = event.target

    setConfirmationForm((currentForm) => ({
      ...currentForm,
      [name]: value,
    }))
    setConfirmationError('')
  }

  // 성찰 전후 인지왜곡 한 건의 사용자 검토 결과 변경 처리.
  const handleDistortionReviewChange = (phase, index, reviewStatus) => {
    const updateReviews = (currentReviews) => currentReviews.map(
      (review, reviewIndex) => (
        reviewIndex === index ? { ...review, reviewStatus } : review
      ),
    )

    if (phase === 'before') {
      setBeforeDistortions(updateReviews)
    } else {
      setAfterDistortions(updateReviews)
    }
    setConfirmationError('')
  }

  // 필수 결과와 인지왜곡 검토를 백엔드 형식으로 변환하여 최종 확정 요청.
  const handleReflectionConfirm = async (event) => {
    event.preventDefault()

    const hasUnreviewedDistortion = [
      ...beforeDistortions,
      ...afterDistortions,
    ].some((distortion) => !distortion.reviewStatus)

    if (!confirmationForm.alternativeThoughtText.trim()) {
      setConfirmationError('대안적 사고를 입력해 주세요.')
      return
    }
    if (hasUnreviewedDistortion) {
      setConfirmationError('제안된 인지왜곡이 맞는지 모두 확인해 주세요.')
      return
    }
    if (!sessionId || isConfirming) return

    setIsConfirming(true)
    setConfirmationError('')

    try {
      await confirmReflection(sessionId, {
        evidenceForText: confirmationForm.evidenceForText.trim(),
        evidenceAgainstText: confirmationForm.evidenceAgainstText.trim(),
        alternativeThoughtText: confirmationForm.alternativeThoughtText.trim(),
        beforeBeliefStrength: Number(confirmationForm.beforeBeliefStrength),
        afterBeliefStrength: Number(confirmationForm.afterBeliefStrength),
        finalEmotionIntensity: Number(confirmationForm.finalEmotionIntensity),
        helpfulnessScore: Number(confirmationForm.helpfulnessScore),
        beforeDistortions,
        afterDistortions,
      })

      setIsConfirmed(true)
      setReflectionStatus('COMPLETED')
    } catch (error) {
      setConfirmationError(getConfirmationErrorMessage(error))
    } finally {
      setIsConfirming(false)
    }
  }

  // API 호출 없이 OPEN 세션을 유지한 채 감정 기록 목록으로 이동하는 처리.
  const handleReflectionLater = () => {
    if (!sessionId || isSending || isConfirming || isCancelling) return

    setSessionActionError('')
    onEmotionHistory()
  }

  // 사용자 확인 후 진행 중인 CBT 세션을 완전히 취소하고 목록으로 이동하는 처리.
  const handleReflectionCancel = async () => {
    if (!sessionId || isSending || isConfirming || isCancelling) return

    const shouldCancel = window.confirm(
      '성찰을 완전히 중단하면 다시 이어할 수 없습니다. 중단하시겠습니까?',
    )

    if (!shouldCancel) return

    setIsCancelling(true)
    setSessionActionError('')

    try {
      await cancelReflection(sessionId)
      setReflectionStatus('CANCELLED')
      onEmotionHistory()
    } catch (error) {
      setSessionActionError(getReflectionErrorMessage(
        error,
        'CBT 성찰을 중단하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      ))
    } finally {
      setIsCancelling(false)
    }
  }

  // 다음 질문 입력 가능 여부 설정.
  const canContinue = reflectionStatus === 'CONTINUE' && !isCrisisBlocked
  // 명확한 인지왜곡이 없다는 AI 판정 여부 설정.
  const hasNoClearDistortion = assessmentType === 'NO_CLEAR_DISTORTION'
  // 사용자가 중단하거나 나중에 이어할 수 있는 OPEN 세션 여부 설정.
  const canManageOpenSession = !isCrisisBlocked && sessionId && (
    reflectionStatus === 'CONTINUE'
    || reflectionStatus === 'CONFIRM_REQUIRED'
  )

  // CBT 소개 화면과 간단한 AI 대화창을 포함한 화면 반환.
  return (
    <main className="cbt-page">
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

      {/* 공통 네비게이션 아래 CBT 성찰 카드를 중앙에 배치하는 영역. */}
      <div className="cbt-content">
        <section
          className="cbt-card"
          aria-labelledby={isChatStarted ? 'cbt-chat-title' : 'cbt-title'}
        >
          <BrandLogo className="cbt-logo" onClick={onHome} />

        {!isChatStarted ? (
          <>
            <h1 id="cbt-title">CBT 성찰</h1>
            <p className="cbt-description">
              감정이 생긴 순간의 생각을 천천히 돌아보는 공간입니다.
            </p>

            {/* 사용자가 CBT 성찰의 기본 목적을 이해할 수 있는 시작 안내 배치. */}
            <div className="cbt-guide">
              <h2>생각 돌아보기</h2>
              <p>
                감정 기록을 바탕으로 당시 떠오른 생각과 새로운 관점을
                AI와의 대화로 차근차근 살펴볼 수 있습니다.
              </p>
            </div>

            {/* CBT AI 대화창을 여는 기본 시작 버튼 배치. */}
            <div className="cbt-actions">
              {!isAutomaticThoughtRequired ? (
                <button
                  className="cbt-start-button"
                  type="button"
                  onClick={handleChatStart}
                  disabled={!emotionRecordId || isStarting}
                >
                  {isStarting ? '기록 확인 중…' : 'CBT 검사 시작하기'}
                </button>
              ) : (
                /* 자동 사고가 없는 기록에 CBT 필수 생각 입력 영역 표시. */
                <form
                  className="cbt-automatic-thought-form"
                  onSubmit={handleAutomaticThoughtSubmit}
                >
                  <div>
                    <h3>CBT 시작 전 생각 확인</h3>
                    <p>
                      당시 상황에서 순간적으로 떠오른 생각을 작성해 주세요.
                    </p>
                  </div>
                  <label htmlFor="cbt-automatic-thought">
                    <span>그 순간 어떤 생각이 떠올랐나요?</span>
                    <textarea
                      id="cbt-automatic-thought"
                      value={automaticThought}
                      onChange={(event) => {
                        setAutomaticThought(event.target.value)
                        setApiError('')
                      }}
                      rows="3"
                      maxLength={maxAnswerLength}
                      placeholder="예: 내일 발표에서 실수하면 모두가 나를 부족하다고 생각할 것 같았다."
                      required
                      disabled={isStarting}
                    />
                  </label>
                  <span className="cbt-message-count">
                    {automaticThought.length}/{maxAnswerLength}
                  </span>
                  <button
                    type="submit"
                    disabled={!automaticThought.trim() || isStarting}
                  >
                    {isStarting ? '저장 후 준비 중…' : '저장하고 CBT 시작하기'}
                  </button>
                </form>
              )}
              {!emotionRecordId && (
                <p className="cbt-error" role="alert">
                  먼저 감정 기록을 저장한 후 CBT 검사를 시작해 주세요.
                </p>
              )}
              {apiError && (
                <p className="cbt-error" role="alert">
                  {apiError}
                </p>
              )}
            </div>
          </>
        ) : (
          <section className="cbt-chat" aria-labelledby="cbt-chat-title">
            {/* CBT AI 대화창의 제목과 간단한 이용 안내 배치. */}
            <header className="cbt-chat-header">
              <h1 id="cbt-chat-title">AI CBT 대화</h1>
              <p>편안한 속도로 떠오르는 생각을 이야기해 주세요.</p>
            </header>

            {/* API가 반환한 AI 질문과 사용자가 전송한 답변을 표시하는 대화 목록 배치. */}
            <div className="cbt-chat-messages" role="log" aria-live="polite">
              {chatMessages.map((chatMessage) => (
                chatMessage.sender === 'ai' ? (
                  <div
                    className="cbt-message cbt-message--ai"
                    key={chatMessage.id}
                  >
                    <strong>Mindot AI</strong>
                    <p>{chatMessage.text}</p>
                  </div>
                ) : (
                  <p
                    className="cbt-message cbt-message--user"
                    key={chatMessage.id}
                  >
                    {chatMessage.text}
                  </p>
                )
              ))}
            </div>

            {/* 대화 진행 상태에서만 사용자 답변 입력과 전송 기능 제공. */}
            {canContinue ? (
              <form className="cbt-chat-form" onSubmit={handleMessageSubmit}>
                <label htmlFor="cbt-message">답변</label>
                <textarea
                  id="cbt-message"
                  value={message}
                  onChange={(event) => {
                    setMessage(event.target.value)
                    setApiError('')
                  }}
                  placeholder="답변을 입력해 주세요."
                  rows="3"
                  maxLength={maxAnswerLength}
                  disabled={isSending}
                />
                <span className="cbt-message-count">
                  {message.length}/{maxAnswerLength}
                </span>
                {apiError && (
                  <p className="cbt-error" role="alert">
                    {apiError}
                  </p>
                )}
                <button type="submit" disabled={!message.trim() || isSending}>
                  {isSending ? '전송 중…' : '보내기'}
                </button>
              </form>
            ) : reflectionStatus === 'CONFIRM_REQUIRED' ? (
              /* AI가 만든 성찰 결과 초안을 검토하고 최종 확정하는 입력 영역 배치. */
              <form
                className="cbt-confirm-form"
                onSubmit={handleReflectionConfirm}
              >
                <header>
                  <h2>
                    {hasNoClearDistortion
                      ? '명확한 인지왜곡 없음 확인'
                      : 'CBT 최종 결과 확인'}
                  </h2>
                  <p>
                    {hasNoClearDistortion
                      ? '현재 생각을 인지왜곡으로 단정하지 않고, 대화를 통해 정리된 내용을 확인해 주세요.'
                      : 'AI가 정리한 내용을 확인하고 필요한 부분을 수정해 주세요.'}
                  </p>
                </header>

                {hasNoClearDistortion && (
                  /* 명확한 인지왜곡이 없다는 판정과 확인 목적 안내 배치. */
                  <div className="cbt-no-clear-distortion" role="status">
                    <strong>명확한 인지왜곡이 확인되지 않았습니다.</strong>
                    <p>
                      생각이 틀렸다고 판단하는 대신, 현재 상황을 균형 있게
                      바라볼 수 있도록 정리한 내용을 확인하는 단계입니다.
                    </p>
                  </div>
                )}

                <label htmlFor="cbt-evidence-for">
                  <span>처음 생각을 뒷받침하는 근거</span>
                  <textarea
                    id="cbt-evidence-for"
                    name="evidenceForText"
                    value={confirmationForm.evidenceForText}
                    onChange={handleConfirmationChange}
                    rows="3"
                    maxLength={maxAnswerLength}
                    disabled={isConfirming}
                  />
                </label>

                <label htmlFor="cbt-evidence-against">
                  <span>처음 생각과 다른 근거</span>
                  <textarea
                    id="cbt-evidence-against"
                    name="evidenceAgainstText"
                    value={confirmationForm.evidenceAgainstText}
                    onChange={handleConfirmationChange}
                    rows="3"
                    maxLength={maxAnswerLength}
                    disabled={isConfirming}
                  />
                </label>

                <label htmlFor="cbt-alternative-thought">
                  <span>대안적 사고</span>
                  <textarea
                    id="cbt-alternative-thought"
                    name="alternativeThoughtText"
                    value={confirmationForm.alternativeThoughtText}
                    onChange={handleConfirmationChange}
                    rows="3"
                    maxLength={maxAnswerLength}
                    required
                    disabled={isConfirming}
                  />
                </label>

                <div className="cbt-confirm-scores">
                  <label htmlFor="cbt-before-belief">
                    <span>성찰 전 생각 확신도</span>
                    <input
                      id="cbt-before-belief"
                      name="beforeBeliefStrength"
                      type="number"
                      value={confirmationForm.beforeBeliefStrength}
                      onChange={handleConfirmationChange}
                      min="0"
                      max="100"
                      placeholder="0~100"
                      required
                      disabled={isConfirming}
                    />
                  </label>
                  <label htmlFor="cbt-after-belief">
                    <span>성찰 후 생각 확신도</span>
                    <input
                      id="cbt-after-belief"
                      name="afterBeliefStrength"
                      type="number"
                      value={confirmationForm.afterBeliefStrength}
                      onChange={handleConfirmationChange}
                      min="0"
                      max="100"
                      placeholder="0~100"
                      required
                      disabled={isConfirming}
                    />
                  </label>
                  <label htmlFor="cbt-final-emotion">
                    <span>현재 감정 강도</span>
                    <input
                      id="cbt-final-emotion"
                      name="finalEmotionIntensity"
                      type="number"
                      value={confirmationForm.finalEmotionIntensity}
                      onChange={handleConfirmationChange}
                      min="0"
                      max="10"
                      placeholder="0~10"
                      required
                      disabled={isConfirming}
                    />
                  </label>
                  <label htmlFor="cbt-helpfulness">
                    <span>성찰 도움 정도</span>
                    <input
                      id="cbt-helpfulness"
                      name="helpfulnessScore"
                      type="number"
                      value={confirmationForm.helpfulnessScore}
                      onChange={handleConfirmationChange}
                      min="0"
                      max="5"
                      placeholder="0~5"
                      required
                      disabled={isConfirming}
                    />
                  </label>
                </div>

                {!hasNoClearDistortion
                  && (beforeDistortions.length > 0 || afterDistortions.length > 0) && (
                  <fieldset className="cbt-distortion-reviews">
                    <legend>인지왜곡 검토</legend>
                    <p>AI가 제안한 항목이 내 생각과 맞는지 확인해 주세요.</p>

                    {beforeDistortions.map((distortion, index) => (
                      <label key={`before-${distortion.code}`}>
                        <span>
                          성찰 전 · {distortionCodeLabels[distortion.code] ?? distortion.code}
                        </span>
                        <select
                          value={distortion.reviewStatus}
                          onChange={(event) => handleDistortionReviewChange(
                            'before',
                            index,
                            event.target.value,
                          )}
                          required
                          disabled={isConfirming}
                        >
                          <option value="" disabled>선택해 주세요</option>
                          <option value="CONFIRMED">맞아요</option>
                          <option value="REJECTED">아니에요</option>
                        </select>
                      </label>
                    ))}

                    {afterDistortions.map((distortion, index) => (
                      <label key={`after-${distortion.code}`}>
                        <span>
                          성찰 후 · {distortionCodeLabels[distortion.code] ?? distortion.code}
                        </span>
                        <select
                          value={distortion.reviewStatus}
                          onChange={(event) => handleDistortionReviewChange(
                            'after',
                            index,
                            event.target.value,
                          )}
                          required
                          disabled={isConfirming}
                        >
                          <option value="" disabled>선택해 주세요</option>
                          <option value="CONFIRMED">맞아요</option>
                          <option value="REJECTED">아니에요</option>
                        </select>
                      </label>
                    ))}
                  </fieldset>
                )}

                {confirmationError && (
                  <p className="cbt-error" role="alert">
                    {confirmationError}
                  </p>
                )}

                <button type="submit" disabled={isConfirming}>
                  {isConfirming
                    ? '확정 중…'
                    : hasNoClearDistortion
                      ? '확인하고 완료하기'
                      : '최종 결과 확정하기'}
                </button>
              </form>
            ) : isConfirmed || reflectionStatus === 'COMPLETED' ? (
              /* CBT 최종 결과 확정 완료 상태 안내. */
              <div className="cbt-confirmed" role="status">
                <strong>CBT 성찰 결과를 확정했습니다.</strong>
                <p>확정한 내용은 이후 마음 패턴을 살펴보는 데 활용됩니다.</p>
              </div>
            ) : (
              <p className="cbt-finished" role="status">
                {reflectionStatus === 'SAFETY_STOP'
                  ? '안전을 위해 대화가 중단되었습니다.'
                  : '현재 CBT 대화가 마무리되었습니다.'}
              </p>
            )}

            {canManageOpenSession && (
              /* OPEN 성찰 세션의 나중에 이어하기와 완전 중단 기능 배치. */
              <div className="cbt-session-actions">
                <p>
                  잠시 멈추면 현재 진행 상태가 유지되며, 목록에서 나중에 이어할 수 있습니다.
                </p>
                {sessionActionError && (
                  <p className="cbt-error" role="alert">
                    {sessionActionError}
                  </p>
                )}
                <div>
                  <button
                    className="cbt-later-button"
                    type="button"
                    onClick={handleReflectionLater}
                    disabled={isSending || isConfirming || isCancelling}
                  >
                    나중에 이어하기
                  </button>
                  <button
                    className="cbt-cancel-button"
                    type="button"
                    onClick={handleReflectionCancel}
                    disabled={isSending || isConfirming || isCancelling}
                  >
                    {isCancelling ? '중단 중…' : '성찰 완전히 중단'}
                  </button>
                </div>
              </div>
            )}
          </section>
        )}
        </section>
      </div>

      {/* CBT 시작 또는 답변 응답에 안전 신호가 있을 때 공통 안전 안내 모달 표시. */}
      {safetyNotice && (
        <SafetyNoticeModal
          notice={safetyNotice}
          onClose={() => setSafetyNotice(null)}
        />
      )}
    </main>
  )
}

export default CBT
