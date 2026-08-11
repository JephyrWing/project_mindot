import { useEffect, useRef, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import './EmotionRecord.css'

// 감정 기록의 최대 입력 글자 수 설정.
const maxContentLength = 1000

// 감정 원문을 입력받는 기본 화면 컴포넌트 정의.
function EmotionRecord() {
  // 감정 원문 입력값 상태 관리.
  const [content, setContent] = useState('')
  // 빈 내용 검증 오류 문구 상태 관리.
  const [inputError, setInputError] = useState('')
  // 작성 및 저장 진행 상태 관리.
  const [saveStatus, setSaveStatus] = useState('idle')
  // 저장 상태 전환 타이머 참조 관리.
  const saveTimerRef = useRef(null)

  // 화면 종료 시 실행 중인 저장 상태 전환 타이머 정리.
  useEffect(() => () => {
    if (saveTimerRef.current) window.clearTimeout(saveTimerRef.current)
  }, [])

  // 감정 원문 변경에 따른 작성 상태 반영.
  const handleContentChange = (event) => {
    setContent(event.target.value)
    setInputError('')
    setSaveStatus('editing')
  }

  // 빈 감정 원문 입력 여부 검사.
  const validateContent = () => {
    const errorMessage = content.trim() ? '' : '내용을 입력해 주세요.'

    setInputError(errorMessage)
    if (errorMessage) setSaveStatus('error')
    return errorMessage === ''
  }

  // 실제 저장 기능 연결 전 저장 상태 전환 처리.
  const handleSubmit = (event) => {
    event.preventDefault()
    if (!validateContent()) return

    setSaveStatus('saving')
    saveTimerRef.current = window.setTimeout(() => {
      setSaveStatus('saved')
      saveTimerRef.current = null
    }, 500)
  }

  // 현재 작성 및 저장 상태에 따른 사용자 표시 문구 설정.
  const statusText = {
    idle: '작성 전',
    editing: '작성 중',
    saving: '저장 중',
    saved: '저장 완료',
    error: '확인 필요',
  }[saveStatus]

  // 간단한 감정 기록 입력 화면 반환.
  return (
    <main className="emotion-record-page">
      <section className="emotion-record-card" aria-labelledby="emotion-record-title">
        <BrandLogo className="emotion-record-logo" />

        <h1 id="emotion-record-title">어떤 감정이 들었나요?</h1>

        {/* 감정 원문 입력창과 기본 버튼 배치. */}
        <form className="emotion-record-form" onSubmit={handleSubmit} noValidate>
          <label htmlFor="emotion-content">지금의 감정</label>
          <textarea
            id="emotion-content"
            value={content}
            onChange={handleContentChange}
            maxLength={maxContentLength}
            placeholder="지금 느끼는 감정을 작성해 주세요."
            disabled={saveStatus === 'saving'}
            aria-invalid={Boolean(inputError)}
            aria-describedby={inputError ? 'emotion-content-error' : undefined}
            required
          />

          {/* 현재 입력 글자 수와 최대 글자 수 표시. */}
          <span className="emotion-record-count">
            {content.length}/{maxContentLength}
          </span>

          {/* 빈 내용 저장 시 사용자 검증 오류 문구 표시. */}
          {inputError && (
            <p className="emotion-record-error" id="emotion-content-error" role="alert">
              {inputError}
            </p>
          )}

          {/* 작성 및 저장 진행 상태 표시. */}
          <div className="emotion-record-status" role="status" aria-live="polite">
            <span>기록 상태</span>
            <strong className={`is-${saveStatus}`}>{statusText}</strong>
          </div>

          <button type="submit" disabled={saveStatus === 'saving'}>
            {saveStatus === 'saving' ? '저장 중…' : '기록하기'}
          </button>
        </form>
      </section>
    </main>
  )
}

export default EmotionRecord
