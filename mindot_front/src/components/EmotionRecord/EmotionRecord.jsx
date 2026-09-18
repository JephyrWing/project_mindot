import { useCallback, useEffect, useRef, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import SafetyNoticeModal from '../SafetyNoticeModal/SafetyNoticeModal.jsx'
import { createQuickRecord } from '../../utils/records/recordsApi.js'
import useVoiceRecorder from './useVoiceRecorder.js'
import { transcribeAudio } from '../../utils/stt/sttApi.js'
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
  if (error.response.status === 403) {
    return '감정 기록을 저장할 권한이 없습니다.'
  }
  if (error.response.status === 404) {
    return '감정 기록을 저장할 대상을 찾을 수 없습니다. 기록 목록으로 돌아가 주세요.'
  }
  if (error.response.status === 409) {
    return error.response.data?.message
      || '같은 감정 기록이 이미 처리되었습니다. 현재 결과를 확인해 주세요.'
  }
  if (error.response.status >= 500) {
    return '서버 오류로 저장하지 못했습니다. 작성한 내용을 유지하고 다시 시도해 주세요.'
  }
  return '저장 결과를 확인하지 못했습니다. 같은 요청으로 다시 확인해 주세요.'
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
  onDailyCare,
  onRecordDetail,
  onHome,
}) {
  // 감정 원문 입력값 상태 관리.
  const [content, setContent] = useState('')
  const pendingSave = useRef(null)
  const saving = useRef(false)
  // 빈 내용 검증 오류 문구 상태 관리.
  const [inputError, setInputError] = useState('')
  // 작성 및 저장 진행 상태 관리.
  const [saveStatus, setSaveStatus] = useState('idle')
  // 백엔드에서 반환한 저장 완료 기록 상태 관리.
  const [savedRecord, setSavedRecord] = useState(null)
  // 감정 기록 API 요청 실패 문구 상태 관리.
  const [saveError, setSaveError] = useState('')
  // 감정 기록 응답에서 반환된 안전 안내 모달 정보 상태 관리.
  const [safetyNotice, setSafetyNotice] = useState(null)

  // 직접 작성하면 TEXT, 음성을 글로 바꾼 뒤 수정하면 VOICE_STT로 저장
  const [inputType, setInputType] = useState('TEXT')
  // 음성을 글로 바꾸는 동안 입력과 저장을 잠그고 진행 상태를 표시
  const [transcribing, setTranscribing] = useState(false)
  // 음성 형식 오류나 STT 요청 실패를 입력창 주변에 안내.
  const [transcriptionError, setTranscriptionError] = useState('')
  // 빠른 연속 클릭으로 같은 음성을 여러 번 전송하지 않도록 즉시 잠그기
  const transcribingRef = useRef(false)
  // 렌더링이 반복돼도 같은 녹음 파일은 자동으로 한 번만 글로 변환
  const autoAttemptedBlobRef = useRef(null)

  const {
    recordingState,
    recordedAudio,
    recordingError,
    startRecording,
    stopRecording,
    resetRecording,
  } = useVoiceRecorder()

  // 녹음 중에는 기록 저장과 새 음성 변환 요청을 막는다
  const recordingBusy = ['requesting', 'recording', 'stopping'].includes(recordingState)

  // 감정 원문 변경에 따른 작성 상태 반영.
  const handleContentChange = (event) => {
    pendingSave.current = null // Editing starts a different logical record.
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

  // 녹음된 음성을 STT API로 전송하고 반환된 문장을 기존 입력창에 채움.
  // 감정 기록 저장 x, 사용자가 수정 가능
  const handleTranscribe = useCallback(async () => {
    if (
      !recordedAudio
      || recordingBusy
      || transcribingRef.current
      || savedRecord
    ) return

    const mimeType = recordedAudio.mimeType.split(';', 2)[0].trim().toLowerCase()
    const extension = {
      'audio/webm': 'webm',
      'video/webm': 'webm',
      'audio/ogg': 'ogg',
    }[mimeType]

    // 알 수 없는 형식을 임의의 확장자로 보내지 않고 사용자에게 알린다
    if (!extension) {
      setTranscriptionError(`지원하지 않는 녹음 형식입니다: ${mimeType || '확인 불가'}`)
      return
    }

    transcribingRef.current = true
    setTranscribing(true)
    setTranscriptionError('')

    try {
      // 음성 파일만 STT API에 보내고 응답받은 텍스트를 정리.
      const transcript = (
        await transcribeAudio(recordedAudio.blob, `recording.${extension}`)
      ).trim()

      if (!transcript) {
        setTranscriptionError('음성에서 문장을 찾지 못했습니다. 다시 녹음해 주세요.')
        return
      }

      // 사용자가 이미 작성한 문장이 있으면 음성에서 바꾼 글을 다음 줄에 추가
      const nextContent = content.trim()
        ? `${content.trim()}\n${transcript}`
        : transcript

      // 기존 감정 기록 입력창의 글자 수 제한을 음성에서 바꾼 글에도 동일하게 적용
      if (nextContent.length > maxContentLength) {
        setTranscriptionError('글자 수가 1,000자를 넘습니다. 기존 글을 줄이거나 짧게 다시 녹음해 주세요.')
        return
      }

      // 사용자는 입력창에서 문장을 고친 뒤 별도의 기록하기 버튼으로 최종 저장.
      pendingSave.current = null
      setContent(nextContent)
      setInputType('VOICE_STT')
      setInputError('')
      setSaveError('')
      setSaveStatus('editing')
    } catch (error) {
      // 백엔드가 제공한 오류 문구가 있으면 표시하고 기존 입력 내용은 유지.
      setTranscriptionError(
        error.response?.data?.message || '음성을 텍스트로 바꾸지 못했습니다.',
      )
    } finally {
      // 성공과 실패 모두 음성 변환 진행 상태와 중복 요청 잠금을 해제
      transcribingRef.current = false
      setTranscribing(false)
    }
  }, [recordedAudio, recordingBusy, savedRecord, content])

  // 녹음 중지가 완료되면 별도 버튼 없이 새 음성 파일을 자동으로 글로 변환
  useEffect(() => {
    if (
      recordingState !== 'ready'
      || !recordedAudio
      || autoAttemptedBlobRef.current === recordedAudio.blob
    ) return

    autoAttemptedBlobRef.current = recordedAudio.blob
    void handleTranscribe()
  }, [recordedAudio, recordingState, handleTranscribe])

  // 녹음을 새로 시작할 때 이전 음성 변환 오류는 지우고 기존 입력 문장은 유지
  const handleRecordingAction = () => {
    if (recordingState === 'recording') {
      stopRecording()
      return
    }

    setTranscriptionError('')
    void startRecording()
  }

  // 저장을 마친 뒤 이전 문장과 음성 상태를 비우고 새 기록을 시작
  const handleNewRecord = () => {
    pendingSave.current = null
    setSavedRecord(null)
    setContent('')
    setSaveStatus('idle')
    setInputType('TEXT')
    autoAttemptedBlobRef.current = null
    setTranscriptionError('')
    setInputError('')
    setSaveError('')
    setSafetyNotice(null)
    resetRecording()
  }

  // 입력한 감정 원문을 백엔드 간편 저장 API로 전달하는 처리.
  const handleSubmit = async (event) => {
    event.preventDefault()
    if (saving.current || transcribingRef.current || recordingBusy || savedRecord) return
    const isContentValid = validateContent()

    if (!isContentValid) {
      setSaveStatus('error')
      return
    }

    saving.current = true
    setSaveStatus('saving')
    setSaveError('')
    setSafetyNotice(null)

    try {
      pendingSave.current ??= {
        key: crypto.randomUUID(),
        // 사용자가 최종 확인한 문장만 저장하고 음성 파일이나 수정 전 문장은 저장하지 않는다
        body: { rawText: content.trim(), inputType, occurredAt: new Date().toISOString() },
      }
      const record = await createQuickRecord(pendingSave.current.body, pendingSave.current.key)

      setSavedRecord(record)
      setSaveStatus('saved')
      setSafetyNotice(record.safetyNotice ?? null)
    } catch (error) {
      setSavedRecord(null)
      setSaveError(getSaveErrorMessage(error))
      setSaveStatus('error')
    } finally { saving.current = false }
  }

  // 즉시 안전 안내가 필요한 저장 결과의 안전 우선 안내 여부 설정.
  const isCrisisNotice = savedRecord?.safetyNotice?.actionCode
    === 'SHOW_CRISIS_NOTICE'

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
        onDailyCare={onDailyCare}
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
            disabled={saveStatus === 'saving' || transcribing}
            aria-invalid={Boolean(inputError)}
            aria-describedby={inputError ? 'emotion-content-error' : undefined}
            required
          />

          {/* 작은 녹음 버튼과 현재 입력 글자 수를 한 줄에 표시 */}
          <div className="emotion-record-input-tools">
            <button
              className={`emotion-record-voice-button${recordingState === 'recording' ? ' is-recording' : ''}`}
              type="button"
              onClick={handleRecordingAction}
              disabled={
                recordingState === 'requesting'
                || recordingState === 'stopping'
                || transcribing
                || saveStatus === 'saving'
                || Boolean(savedRecord)
              }
              aria-pressed={recordingState === 'recording'}
            >
              {recordingState === 'recording' ? (
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <rect x="6" y="6" width="12" height="12" rx="2" />
                </svg>
              ) : (
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <rect x="9" y="2" width="6" height="12" rx="3" />
                  <path d="M5 10a7 7 0 0 0 14 0M12 17v5M8 22h8" />
                </svg>
              )}
              <span>
                {recordingState === 'recording'
                  ? '녹음 중지'
                  : recordingState === 'requesting'
                    ? '마이크 연결 중…'
                    : recordingState === 'stopping'
                      ? '녹음 마무리 중…'
                      : recordedAudio
                        ? '다시 녹음'
                        : '음성 녹음'}
              </span>
            </button>
            <span className="emotion-record-count">
              {content.length}/{maxContentLength}
            </span>
          </div>

          {/* 녹음이 끝난 음성은 자동으로 글로 바꾸고 진행 상태만 안내 */}
          {transcribing && (
            <p className="emotion-record-transcribing" role="status">
              녹음 내용을 글로 옮기는 중입니다…
            </p>
          )}

          {/* 음성을 글로 바꾸지 못했을 때 입력 문장을 유지하고 재시도 버튼 표시 */}
          {transcriptionError && (
            <div className="emotion-record-transcription-feedback">
              <p className="emotion-record-error" role="alert">
                {transcriptionError}
              </p>
              {recordedAudio && recordingState === 'ready' && (
                <button
                  className="emotion-record-retry-button"
                  type="button"
                  onClick={() => { void handleTranscribe() }}
                >
                  다시 시도
                </button>
              )}
            </div>
          )}

          {recordingError && (
           <p className="emotion-record-error" role="alert">
             {recordingError}
            </p>
          )}

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

          <button
            type="submit"
             disabled={
               saveStatus === 'saving'
              || transcribing
              || recordingBusy
               || Boolean(savedRecord)
              }
            >
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
              <p role="status">{savedRecord.analysisStatus === 'FAILED'
                ? '원문은 저장되었습니다. AI 분석에 실패했으니 상세 화면에서 재분석해 주세요.'
                : ['PENDING', 'PROCESSING'].includes(savedRecord.analysisStatus)
                  ? '원문은 저장되었고 AI 분석은 진행 중입니다. 상세 화면에서 상태를 확인해 주세요.'
                  : '원문 저장이 완료되었습니다.'}</p>
              <button
                className="emotion-record-new-button"
                type="button"
                onClick={handleNewRecord}
              >
                새 기록 작성
              </button>
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

          {/* 저장 직후 생성된 기록 식별자를 사용한 상세 확인 화면 이동 버튼 표시. */}
          {saveStatus === 'saved' && savedRecord && (
            <button
              className="emotion-record-detail-button"
              type="button"
              onClick={() => onRecordDetail(savedRecord.recordId)}
            >
              감정 기록 상세 확인하기
            </button>
          )}

          {/* 위기 안전 신호가 확인된 기록의 안전 우선 안내 표시. */}
          {saveStatus === 'saved' && isCrisisNotice && (
            <p className="emotion-record-safety-guidance" role="status">
              현재는 CBT 성찰보다 즉시 안전을 확인하고 주변 또는 전문기관에
              도움을 요청하는 일이 우선입니다.
            </p>
          )}
          </form>
        </section>
      </div>

      {/* 간편 감정 기록 응답에 안전 신호가 있을 때 공통 안전 안내 모달 표시. */}
      {safetyNotice && (
        <SafetyNoticeModal
          notice={safetyNotice}
          onClose={() => setSafetyNotice(null)}
        />
      )}
    </main>
  )
}

export default EmotionRecord
