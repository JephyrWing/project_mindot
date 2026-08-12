import { useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import './CBT.css'

// 감정 기록을 바탕으로 생각을 돌아보는 기본 CBT 성찰 화면 컴포넌트 정의.
function CBT() {
  // CBT AI 대화창 시작 여부 상태 관리.
  const [isChatStarted, setIsChatStarted] = useState(false)
  // 사용자가 작성 중인 답변 내용 상태 관리.
  const [message, setMessage] = useState('')
  // 화면에 임시로 표시할 사용자 답변 목록 상태 관리.
  const [userMessages, setUserMessages] = useState([])

  // 실제 AI API 연결 전 사용자 답변을 화면 말풍선으로 추가하는 처리.
  const handleMessageSubmit = (event) => {
    event.preventDefault()

    const trimmedMessage = message.trim()
    if (!trimmedMessage) return

    setUserMessages((currentMessages) => [...currentMessages, trimmedMessage])
    setMessage('')
  }

  // CBT 소개 화면과 간단한 AI 대화창을 포함한 화면 반환.
  return (
    <main className="cbt-page">
      <section
        className="cbt-card"
        aria-labelledby={isChatStarted ? 'cbt-chat-title' : 'cbt-title'}
      >
        <BrandLogo className="cbt-logo" />

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
                onClick={() => setIsChatStarted(true)}
              >
                CBT 검사 시작하기
              </button>
            </div>
          </>
        ) : (
          <section className="cbt-chat" aria-labelledby="cbt-chat-title">
            {/* CBT AI 대화창의 제목과 간단한 이용 안내 배치. */}
            <header className="cbt-chat-header">
              <h1 id="cbt-chat-title">AI CBT 대화</h1>
              <p>편안한 속도로 떠오르는 생각을 이야기해 주세요.</p>
            </header>

            {/* AI의 첫 질문과 사용자가 입력한 답변을 표시하는 대화 목록 배치. */}
            <div className="cbt-chat-messages" role="log" aria-live="polite">
              <div className="cbt-message cbt-message--ai">
                <strong>Mindot AI</strong>
                <p>감정을 기록한 순간, 어떤 일이 있었는지 이야기해 주실래요?</p>
              </div>

              {userMessages.map((userMessage, index) => (
                <p className="cbt-message cbt-message--user" key={`${userMessage}-${index}`}>
                  {userMessage}
                </p>
              ))}
            </div>

            {/* 실제 AI 응답 연결 전 사용자 답변을 입력하고 표시하는 기본 폼 배치. */}
            <form className="cbt-chat-form" onSubmit={handleMessageSubmit}>
              <label htmlFor="cbt-message">답변</label>
              <textarea
                id="cbt-message"
                value={message}
                onChange={(event) => setMessage(event.target.value)}
                placeholder="답변을 입력해 주세요."
                rows="3"
              />
              <button type="submit" disabled={!message.trim()}>
                보내기
              </button>
            </form>
          </section>
        )}
      </section>
    </main>
  )
}

export default CBT
