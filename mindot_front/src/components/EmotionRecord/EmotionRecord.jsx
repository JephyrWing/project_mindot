import { useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import { createQuickRecord } from '../../utils/records/recordsApi.js'
import './EmotionRecord.css'

// 감정 기록의 최대 입력 글자 수 설정.
const maxContentLength = 1000
// 현재 감정과 가장 가까운 항목을 선택하기 위한 기본 감정 목록 설정.
const emotionOptions = ['기쁨', '평온', '슬픔', '불안', '화남']
// 선택한 감정의 강도를 다섯 단계로 구분하기 위한 목록 설정.
const emotionIntensityOptions = ['매우 약함', '약함', '보통', '강함', '매우 강함']
// 감정이 생긴 배경을 간단히 분류하기 위한 상황 목록 설정.
const contextOptions = [
  '일·학업',
  '가족',
  '대인관계',
  '건강',
  '일상·기타',
]
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

// 감정 원문을 입력받는 기본 화면 컴포넌트 정의.
function EmotionRecord({ onCBT, onWeeklyReport, onHome }) {
  // 사용자가 선택한 대표 감정 상태 관리.
  const [selectedEmotion, setSelectedEmotion] = useState('')
  // 사용자가 선택한 감정 강도 단계 상태 관리.
  const [selectedIntensity, setSelectedIntensity] = useState(0)
  // 사용자가 선택한 감정 발생 상황 상태 관리.
  const [selectedContext, setSelectedContext] = useState('')
  // 감정 원문 입력값 상태 관리.
  const [content, setContent] = useState('')
  // 감정과 강도 및 상황 선택 오류 문구 상태 관리.
  const [selectionError, setSelectionError] = useState('')
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
    setSelectionError('')
    setSaveError('')
    setSavedRecord(null)
    setSaveStatus('editing')
  }

  // 감정 항목 선택과 같은 항목 재선택 시 해제 및 작성 상태 반영 처리.
  const handleEmotionSelect = (emotion) => {
    setSelectedEmotion(selectedEmotion === emotion ? '' : emotion)
    setSelectedIntensity(0)
    setSelectedContext('')
    setSelectionError('')
    setSaveError('')
    setSavedRecord(null)
    setSaveStatus('editing')
  }

  // 선택한 감정 강도 단계 반영과 작성 상태 변경 처리.
  const handleIntensitySelect = (intensity) => {
    setSelectedIntensity(intensity)
    setSelectionError('')
    setSaveError('')
    setSavedRecord(null)
    setSaveStatus('editing')
  }

  // 감정이 발생한 상황 선택과 같은 항목 재선택 시 해제 처리.
  const handleContextSelect = (context) => {
    setSelectedContext(selectedContext === context ? '' : context)
    setSelectionError('')
    setSaveError('')
    setSavedRecord(null)
    setSaveStatus('editing')
  }

  // 감정과 강도 및 상황의 순차 선택 여부 검사.
  const validateSelections = () => {
    let errorMessage = ''

    if (!selectedEmotion) {
      errorMessage = '가장 가까운 감정을 선택해 주세요.'
    } else if (!selectedIntensity) {
      errorMessage = '감정의 강도를 선택해 주세요.'
    } else if (!selectedContext) {
      errorMessage = '감정이 생긴 상황을 선택해 주세요.'
    }

    setSelectionError(errorMessage)
    return errorMessage === ''
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
    const areSelectionsValid = validateSelections()
    // 앞 단계 선택 완료 후에만 사용자가 입력할 수 있는 감정 원문 검사.
    const isContentValid = areSelectionsValid ? validateContent() : true

    if (!areSelectionsValid) setInputError('')

    if (!areSelectionsValid || !isContentValid) {
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
    setSelectedEmotion('')
    setSelectedIntensity(0)
    setSelectedContext('')
    setContent('')
    setSelectionError('')
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
      <section className="emotion-record-card" aria-labelledby="emotion-record-title">
        <BrandLogo className="emotion-record-logo" onClick={onHome} />

        <h1 id="emotion-record-title">어떤 감정이 들었나요?</h1>

        {/* 감정 원문 입력창과 기본 버튼 배치. */}
        <form className="emotion-record-form" onSubmit={handleSubmit} noValidate>
          {/* 현재 마음과 가까운 대표 감정 하나를 선택하는 버튼 영역 배치. */}
          <fieldset className="emotion-record-selector">
            <legend>가장 가까운 감정</legend>
            <div className="emotion-record-options">
              {emotionOptions.map((emotion) => (
                <button
                  className="emotion-record-option"
                  type="button"
                  key={emotion}
                  onClick={() => handleEmotionSelect(emotion)}
                  aria-pressed={selectedEmotion === emotion}
                  disabled={saveStatus === 'saving'}
                >
                  {emotion}
                </button>
              ))}
            </div>
          </fieldset>

          {/* 대표 감정을 선택한 뒤 현재 감정의 강도를 고르는 단계 영역 배치. */}
          <fieldset
            className="emotion-record-intensity"
            disabled={!selectedEmotion || saveStatus === 'saving'}
          >
            <legend>감정 강도</legend>
            <div className="emotion-record-intensity-options">
              {emotionIntensityOptions.map((intensityLabel, index) => {
                const intensity = index + 1

                return (
                  <button
                    className="emotion-record-intensity-button"
                    type="button"
                    key={intensityLabel}
                    onClick={() => handleIntensitySelect(intensity)}
                    aria-label={`${intensity}단계 ${intensityLabel}`}
                    aria-pressed={selectedIntensity === intensity}
                  >
                    {intensity}
                  </button>
                )
              })}
            </div>
            <p className="emotion-record-intensity-guide" aria-live="polite">
              {selectedEmotion
                ? selectedIntensity
                  ? `${selectedEmotion} · ${emotionIntensityOptions[selectedIntensity - 1]}`
                  : '감정의 강도를 선택해 주세요.'
                : '대표 감정을 먼저 선택해 주세요.'}
            </p>
          </fieldset>

          {/* 감정 강도 선택 후 감정이 생긴 상황을 고르는 단계 영역 배치. */}
          <fieldset
            className="emotion-record-context"
            disabled={!selectedIntensity || saveStatus === 'saving'}
          >
            <legend>어떤 상황이었나요?</legend>
            <div className="emotion-record-context-options">
              {contextOptions.map((context) => (
                <button
                  className="emotion-record-context-button"
                  type="button"
                  key={context}
                  onClick={() => handleContextSelect(context)}
                  aria-pressed={selectedContext === context}
                >
                  {context}
                </button>
              ))}
            </div>
            <p className="emotion-record-context-guide" aria-live="polite">
              {selectedIntensity
                ? selectedContext || '가장 가까운 상황을 선택해 주세요.'
                : '감정 강도를 먼저 선택해 주세요.'}
            </p>
          </fieldset>

          {/* 감정 기록의 앞 단계 선택 누락 시 사용자 안내 문구 표시. */}
          {selectionError && (
            <p className="emotion-record-error" role="alert">
              {selectionError}
            </p>
          )}

          <label htmlFor="emotion-content">지금의 감정</label>
          <textarea
            id="emotion-content"
            value={content}
            onChange={handleContentChange}
            maxLength={maxContentLength}
            placeholder={
              selectedContext
                ? '지금 느끼는 감정을 작성해 주세요.'
                : '상황 선택 후 입력 가능'
            }
            disabled={!selectedContext || saveStatus === 'saving'}
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

          {/* 백엔드 저장 완료 결과와 사용자가 선택한 내용을 요약하여 표시. */}
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
                  <dt>감정</dt>
                  <dd>{selectedEmotion} · {selectedIntensity}단계</dd>
                </div>
                <div>
                  <dt>상황</dt>
                  <dd>{selectedContext}</dd>
                </div>
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
    </main>
  )
}

export default EmotionRecord
