import { useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import { createQuickRecord } from '../../utils/records/recordsApi.js'
import './EmotionRecord.css'

// 감정 기록의 최대 입력 글자 수 설정.
const maxContentLength = 1000
// 백엔드 시간대 코드를 사용자 안내 문구로 바꾸기 위한 목록 설정.
const timeBucketLabels = {
  DAWN: '새벽',
  MORNING: '아침',
  AFTERNOON: '오후',
  EVENING: '저녁',
  NIGHT: '밤',
}
// 백엔드 평일 및 주말 코드를 사용자 안내 문구로 바꾸기 위한 목록 설정.
const weekdayTypeLabels = {
  WEEKDAY: '평일',
  WEEKEND: '주말',
}

// 감정 기록 API 오류 상태에 따른 사용자 안내 문구 반환.
const getSaveErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  return '감정 기록을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 저장 시각을 한국어 날짜와 시간 형식으로 변환.
const formatOccurredAt = (occurredAt) => new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'medium',
  timeStyle: 'short',
}).format(new Date(occurredAt))

// 공통 네비게이션과 감정 원문 입력 영역을 제공하는 화면 컴포넌트 정의.
function EmotionRecord({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onCBT,
  onWeeklyReport,
  onHome,
}) {
  // 감정 원문 입력값 상태 관리.
  const [content, setContent] = useState('')
  // 빈 내용 검증 오류 문구 상태 관리.
  const [inputError, setInputError] = useState('')
  // 작성 및 저장 진행 상태 관리.
  const [saveStatus, setSaveStatus] = useState('idle')
  // 백엔드에서 반환한 저장 완료 기록 상태 관리.
  const [savedRecord, setSavedRecord] = useState(null)
  // 감정 기록 API 요청 실패 문구 상태 관리.
  const [saveError, setSaveError] = useState('')

  // 감정 원문 변경에 따른 작성 상태 반영.
  const handleContentChange = (event) => {
    setContent(event.target.value)
    setInputError('')
    setSaveError('')
    setSavedRecord(null)
    setSaveStatus('editing')
  }

  // 빈 감정 원문 입력 여부 검사.
  const validateContent = () => {
    const errorMessage = content.trim() ? '' : '지금의 감정을 입력해 주세요.'

    setInputError(errorMessage)
    return errorMessage === ''
  }

  // 입력한 감정 원문을 백엔드 간편 저장 API로 전달하는 처리.
  const handleSubmit = async (event) => {
    event.preventDefault()
    const isContentValid = validateContent()

    if (!isContentValid) {
      setSaveStatus('error')
      return
    }

    setSaveStatus('saving')
    setSaveError('')

    try {
      const record = await createQuickRecord({
        rawText: content.trim(),
        inputType: 'TEXT',
        occurredAt: new Date().toISOString(),
      })

      setSavedRecord(record)
      setSaveStatus('saved')
    } catch (error) {
      setSavedRecord(null)
      setSaveError(getSaveErrorMessage(error))
      setSaveStatus('error')
    }
  }

  // 저장 완료 후 새로운 감정 기록을 작성하기 위한 전체 입력값 초기화.
  const handleReset = () => {
    setContent('')
    setInputError('')
    setSaveError('')
    setSavedRecord(null)
    setSaveStatus('idle')
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
      {/* 주요 화면 이동과 인증 메뉴를 제공하는 공통 상단 네비게이션 배치. */}
      <Navbar
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={onLogin}
        onLogout={onLogout}
        onSignUp={onSignUp}
        onEmotionHistory={onEmotionHistory}
        onCenter={onCenter}
        onHome={onHome}
      />

      {/* 네비게이션 아래 감정 기록 카드를 중앙에 배치하는 콘텐츠 영역 설정. */}
      <div className="emotion-record-content">
        <section className="emotion-record-card" aria-labelledby="emotion-record-title">
          <BrandLogo className="emotion-record-logo" onClick={onHome} />

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

          {/* 감정 기록 API 요청 실패 시 사용자 안내 문구 표시. */}
          {saveError && (
            <p className="emotion-record-error" role="alert">
              {saveError}
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

          {/* 백엔드 저장 완료 결과의 기록 시각을 요약하여 표시. */}
          {saveStatus === 'saved' && savedRecord && (
            <section
              className="emotion-record-summary"
              aria-labelledby="emotion-record-summary-title"
            >
              <div className="emotion-record-summary-header">
                <h2 id="emotion-record-summary-title">기록 완료</h2>
                <span>
                  {weekdayTypeLabels[savedRecord.weekdayType]
                    || savedRecord.weekdayType}
                </span>
              </div>
              <dl>
                <div>
                  <dt>기록 시각</dt>
                  <dd>
                    {formatOccurredAt(savedRecord.occurredAt)} ·{' '}
                    {timeBucketLabels[savedRecord.timeBucket]
                      || savedRecord.timeBucket}
                  </dd>
                </div>
              </dl>
            </section>
          )}

          {/* 감정 기록 저장 완료 후에만 CBT 성찰 화면 이동 버튼 표시. */}
          {saveStatus === 'saved' && (
            <button
              className="emotion-record-cbt-button"
              type="button"
              onClick={onCBT}
            >
              CBT 검사 하기
            </button>
          )}

          {/* 감정 기록 저장 완료 후에만 주간 리포트 화면 이동 버튼 표시. */}
          {saveStatus === 'saved' && (
            <button
              className="emotion-record-report-button"
              type="button"
              onClick={onWeeklyReport}
            >
              주간 리포트로 이동하기
            </button>
          )}

          {/* 저장 완료 후 현재 입력값을 비우고 새 기록을 시작하는 버튼 표시. */}
          {saveStatus === 'saved' && (
            <button
              className="emotion-record-reset-button"
              type="button"
              onClick={handleReset}
            >
              새 기록 작성하기
            </button>
          )}
          </form>
        </section>
      </div>
    </main>
  )
}

export default EmotionRecord
