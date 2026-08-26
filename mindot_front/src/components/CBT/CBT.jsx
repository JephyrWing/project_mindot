import { useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import {
  startReflection,
  submitReflectionAnswer,
} from '../../utils/reflections/reflectionsApi.js'
import './CBT.css'

// CBT 답변의 백엔드 최대 허용 글자 수 설정.
const maxAnswerLength = 4000

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

// CBT API 응답 상태에 맞는 AI 안내 문구 반환.
const getResponseMessage = (response) => {
  if (response.nextQuestion?.question) {
    return response.nextQuestion.question
  }
  if (response.proposalMessage) {
    return response.proposalMessage
  }
  if (response.status === 'CONFIRM_REQUIRED') {
    return '대화를 바탕으로 성찰 결과가 준비되었습니다.'
  }
  if (response.status === 'SAFETY_STOP') {
    return '안전을 위해 CBT 대화를 잠시 중단합니다. 즉각적인 도움이 필요하면 112 또는 119에 연락해 주세요.'
  }

  return 'CBT 성찰 대화가 마무리되었습니다.'
}

// 감정 기록을 바탕으로 생각을 돌아보는 기본 CBT 성찰 화면 컴포넌트 정의.
function CBT({
  emotionRecordId,
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
  const [isChatStarted, setIsChatStarted] = useState(false)
  // 사용자가 작성 중인 답변 내용 상태 관리.
  const [message, setMessage] = useState('')
  // API에서 주고받은 AI 질문과 사용자 답변 목록 상태 관리.
  const [chatMessages, setChatMessages] = useState([])
  // 백엔드가 생성한 CBT 성찰 세션 식별자 상태 관리.
  const [sessionId, setSessionId] = useState(null)
  // CBT 세션 시작 요청 진행 여부 상태 관리.
  const [isStarting, setIsStarting] = useState(false)
  // CBT 답변 전송 요청 진행 여부 상태 관리.
  const [isSending, setIsSending] = useState(false)
  // CBT API 요청 실패 안내 문구 상태 관리.
  const [apiError, setApiError] = useState('')
  // CBT 대화 계속 여부를 판단하기 위한 백엔드 응답 상태 관리.
  const [reflectionStatus, setReflectionStatus] = useState('IDLE')

  // 저장된 감정 기록을 사용하여 첫 CBT 질문을 요청하는 처리.
  const handleChatStart = async () => {
    if (!emotionRecordId || isStarting) return

    setIsStarting(true)
    setApiError('')

    try {
      const response = await startReflection(emotionRecordId)

      setSessionId(response.sessionId)
      setReflectionStatus(response.status)
      setChatMessages([{
        id: `ai-${response.sessionId}-start`,
        sender: 'ai',
        text: getResponseMessage(response),
      }])
      setIsChatStarted(true)
    } catch (error) {
      setApiError(getReflectionErrorMessage(
        error,
        'CBT 대화를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      ))
    } finally {
      setIsStarting(false)
    }
  }

  // 현재 CBT 질문의 답변을 백엔드에 전달하고 다음 AI 질문을 표시하는 처리.
  const handleMessageSubmit = async (event) => {
    event.preventDefault()

    const trimmedMessage = message.trim()
    if (!trimmedMessage || !sessionId || isSending) return

    setIsSending(true)
    setApiError('')

    try {
      const response = await submitReflectionAnswer(sessionId, trimmedMessage)

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

  // 다음 질문 입력 가능 여부 설정.
  const canContinue = reflectionStatus === 'CONTINUE'

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
              <button
                className="cbt-start-button"
                type="button"
                onClick={handleChatStart}
                disabled={!emotionRecordId || isStarting}
              >
                {isStarting ? '대화 준비 중…' : 'CBT 검사 시작하기'}
              </button>
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
            ) : (
              <p className="cbt-finished" role="status">
                {reflectionStatus === 'SAFETY_STOP'
                  ? '안전을 위해 대화가 중단되었습니다.'
                  : '현재 CBT 대화가 마무리되었습니다.'}
              </p>
            )}
          </section>
        )}
        </section>
      </div>
    </main>
  )
}

export default CBT
