import { useEffect, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import {
  deleteEmotionRecord,
  getEmotionRecordDetail,
  updateEmotionRecordOccurredAt,
} from '../../utils/records/recordsApi.js'
import './EmotionRecordDetail.css'

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
  OTHER: '기타',
}

// 백엔드 상황 코드를 사용자에게 표시할 한국어 이름으로 변환하기 위한 목록 설정.
const contextCategoryLabels = {
  SOCIAL_EVALUATION: '사회적 평가',
  PERFORMANCE: '발표·시험',
  PROMISE: '약속',
  MISTAKE: '실수',
  CONFLICT: '갈등',
  REJECTION: '거절·소외',
  WORK: '업무',
  STUDY: '학업',
  HEALTH: '건강',
  DAILY_LIFE: '일상',
  OTHER: '기타',
}

// 기록 시간대 코드를 사용자에게 표시할 한국어 이름으로 변환하기 위한 목록 설정.
const timeBucketLabels = {
  DAWN: '새벽',
  MORNING: '아침',
  AFTERNOON: '오후',
  EVENING: '저녁',
  NIGHT: '밤',
}

// 기록 완성 상태를 사용자에게 표시할 한국어 이름으로 변환하기 위한 목록 설정.
const completionStatusLabels = {
  QUICK: '간편 기록',
  PARTIAL: '분석 확인 필요',
  COMPLETE: '기록 완료',
}

// 비어 있는 상세 항목에 공통으로 표시할 안내 문구 반환.
const getDisplayValue = (value, fallback = '분석 전') => (
  value === null || value === undefined || value === '' ? fallback : value
)

// 감정 기록 시각을 사용자가 읽기 쉬운 한국어 형식으로 변환하는 함수 정의.
const formatDetailDate = (occurredAt) => new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  weekday: 'short',
  hour: '2-digit',
  minute: '2-digit',
}).format(new Date(occurredAt))

// API의 UTC 시각을 사용자의 현재 지역 기준 날짜 및 시간 입력값으로 변환.
const toDateTimeLocalValue = (occurredAt) => {
  const occurredDate = new Date(occurredAt)

  if (Number.isNaN(occurredDate.getTime())) return ''

  const timezoneOffset = occurredDate.getTimezoneOffset() * 60 * 1000

  return new Date(occurredDate.getTime() - timezoneOffset)
    .toISOString()
    .slice(0, 16)
}

// 미래 시각 선택 방지를 위한 현재 지역 기준 날짜 및 시간 최댓값 생성.
const getCurrentDateTimeLocalValue = () => toDateTimeLocalValue(new Date())

// 감정 기록 상세 API 오류 상태에 따른 사용자 안내 문구 반환.
const getDetailErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (error.response.status === 404) {
    return '선택한 감정 기록을 찾을 수 없습니다.'
  }

  return '감정 기록 상세 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 감정 발생 시각 수정 API 오류 상태에 따른 사용자 안내 문구 반환.
const getUpdateErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 400) {
    return '선택한 날짜와 시간을 확인해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (error.response.status === 404) {
    return '수정할 감정 기록을 찾을 수 없습니다.'
  }

  return '감정 발생 시각을 수정하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 감정 기록 삭제 API 오류 상태에 따른 사용자 안내 문구 반환.
const getDeleteErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (error.response.status === 404) {
    return '삭제할 감정 기록을 찾을 수 없습니다.'
  }

  return '감정 기록을 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 선택한 감정 기록 한 건을 API로 조회하고 상세 정보를 제공하는 화면 정의.
function EmotionRecordDetail({
  emotionRecordId,
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
  // 백엔드에서 조회한 감정 기록 상세 정보 상태 설정.
  const [record, setRecord] = useState(null)
  // 감정 기록 상세 API 요청 진행 여부 상태 설정.
  const [isLoading, setIsLoading] = useState(true)
  // 감정 기록 상세 API 요청 실패 안내 문구 상태 설정.
  const [loadError, setLoadError] = useState('')
  // 사용자가 상세 재조회 버튼을 선택한 횟수 상태 설정.
  const [reloadCount, setReloadCount] = useState(0)
  // 사용자가 수정할 감정 발생 날짜와 시간 입력값 상태 설정.
  const [occurredAtInput, setOccurredAtInput] = useState('')
  // 감정 발생 시각 수정 API 요청 진행 여부 상태 설정.
  const [isUpdatingOccurredAt, setIsUpdatingOccurredAt] = useState(false)
  // 감정 발생 시각 수정 결과 안내 문구 상태 설정.
  const [occurredAtMessage, setOccurredAtMessage] = useState('')
  // 감정 발생 시각 수정 실패 여부 상태 설정.
  const [isOccurredAtError, setIsOccurredAtError] = useState(false)
  // 사용자의 감정 기록 삭제 확인 영역 표시 여부 상태 설정.
  const [isDeleteConfirmOpen, setIsDeleteConfirmOpen] = useState(false)
  // 감정 기록 삭제 API 요청 진행 여부 상태 설정.
  const [isDeleting, setIsDeleting] = useState(false)
  // 감정 기록 삭제 API 요청 실패 안내 문구 상태 설정.
  const [deleteError, setDeleteError] = useState('')

  // 화면 진입과 재조회 시 선택한 감정 기록의 상세 정보 요청.
  useEffect(() => {
    let isActive = true

    const loadEmotionRecordDetail = async () => {
      if (!emotionRecordId) {
        setIsLoading(false)
        setLoadError('조회할 감정 기록을 선택해 주세요.')
        return
      }

      setIsLoading(true)
      setLoadError('')

      try {
        const detail = await getEmotionRecordDetail(emotionRecordId)

        if (isActive) {
          setRecord(detail)
          setOccurredAtInput(toDateTimeLocalValue(detail.occurredAt))
          setOccurredAtMessage('')
          setIsOccurredAtError(false)
        }
      } catch (error) {
        if (isActive) {
          setRecord(null)
          setLoadError(getDetailErrorMessage(error))
        }
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadEmotionRecordDetail()

    return () => {
      isActive = false
    }
  }, [emotionRecordId, reloadCount])

  // 보조 감정 목록의 코드와 강도를 한글 문구로 변환.
  const secondaryEmotionText = record?.secondaryEmotions?.length
    ? record.secondaryEmotions.map((emotion) => {
      const code = emotion.code ?? emotion.emotionCode ?? emotion.name
      const label = emotionCodeLabels[code] ?? code ?? '기타'
      const intensity = emotion.intensity ?? emotion.score

      return intensity === null || intensity === undefined
        ? label
        : `${label} ${intensity}/10`
    }).join(', ')
    : '분석 전'

  // 입력한 지역 시각을 UTC 형식으로 변환하여 감정 발생 시각 수정 요청.
  const handleOccurredAtUpdate = async (event) => {
    event.preventDefault()

    const selectedDate = new Date(occurredAtInput)

    if (!occurredAtInput || Number.isNaN(selectedDate.getTime())) {
      setOccurredAtMessage('수정할 날짜와 시간을 선택해 주세요.')
      setIsOccurredAtError(true)
      return
    }

    if (selectedDate.getTime() > Date.now()) {
      setOccurredAtMessage('현재보다 이후의 시간은 선택할 수 없습니다.')
      setIsOccurredAtError(true)
      return
    }

    setIsUpdatingOccurredAt(true)
    setOccurredAtMessage('')
    setIsOccurredAtError(false)

    try {
      const updatedRecord = await updateEmotionRecordOccurredAt(
        emotionRecordId,
        selectedDate.toISOString(),
      )

      setRecord(updatedRecord)
      setOccurredAtInput(toDateTimeLocalValue(updatedRecord.occurredAt))
      setOccurredAtMessage('감정 발생 시각을 수정했습니다.')
    } catch (error) {
      setOccurredAtMessage(getUpdateErrorMessage(error))
      setIsOccurredAtError(true)
    } finally {
      setIsUpdatingOccurredAt(false)
    }
  }

  // 사용자가 최종 확인한 감정 기록과 연결된 CBT 성찰 데이터 삭제 요청.
  const handleEmotionRecordDelete = async () => {
    if (isDeleting) return

    setIsDeleting(true)
    setDeleteError('')

    try {
      await deleteEmotionRecord(emotionRecordId)
      onBack()
    } catch (error) {
      setDeleteError(getDeleteErrorMessage(error))
      setIsDeleting(false)
    }
  }

  // 공통 네비게이션과 상세 조회 상태 및 결과 화면 반환.
  return (
    <main className="emotion-detail-page">
      {/* 인증 상태와 주요 화면 이동 기능을 제공하는 공통 상단 네비게이션 배치. */}
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

      {/* 선택한 감정 기록의 상세 정보를 담는 단일 테두리 콘텐츠 영역 배치. */}
      <section className="emotion-detail-content" aria-labelledby="emotion-detail-title">
        <BrandLogo className="emotion-detail-logo" onClick={onHome} />

        <div className="emotion-detail-heading">
          <div>
            <h1 id="emotion-detail-title">감정 기록 상세</h1>
            <p>선택한 날의 감정과 생각을 자세히 확인하는 공간입니다.</p>
          </div>
          <button type="button" onClick={onBack}>목록으로</button>
        </div>

        {isLoading ? (
          /* 감정 기록 상세 API 요청 중 사용자에게 진행 상태 안내. */
          <div className="emotion-detail-state" aria-live="polite" aria-busy="true">
            <h2>감정 기록을 불러오는 중입니다.</h2>
            <p>잠시만 기다려 주세요.</p>
          </div>
        ) : loadError ? (
          /* 감정 기록 상세 API 요청 실패 시 오류 원인과 재조회 기능 안내. */
          <div className="emotion-detail-state" role="alert">
            <h2>상세 정보를 불러오지 못했습니다.</h2>
            <p>{loadError}</p>
            <div className="emotion-detail-state-actions">
              <button type="button" onClick={onBack}>목록으로</button>
              {emotionRecordId && (
                <button
                  type="button"
                  onClick={() => setReloadCount((currentCount) => currentCount + 1)}
                >
                  다시 불러오기
                </button>
              )}
            </div>
          </div>
        ) : record ? (
          /* 상세 API 응답에서 받은 감정 기록 원문과 분석 정보 표시. */
          <div className="emotion-detail-result">
            <div className="emotion-detail-summary">
              <div className="emotion-detail-tags">
                <strong>
                  {emotionCodeLabels[record.primaryEmotionCode] ?? '분석 전'}
                </strong>
                <span>
                  {record.primaryIntensity === null || record.primaryIntensity === undefined
                    ? '강도 분석 전'
                    : `강도 ${record.primaryIntensity}/10`}
                </span>
                <span>
                  {contextCategoryLabels[record.contextCategory] ?? '상황 미분류'}
                </span>
              </div>
              <time dateTime={record.occurredAt}>
                {formatDetailDate(record.occurredAt)}
              </time>
            </div>

            {/* 실제 감정이 발생한 날짜와 시간을 수정하는 입력 영역 배치. */}
            <section
              className="emotion-detail-time-editor"
              aria-labelledby="emotion-detail-time-title"
            >
              <div>
                <h2 id="emotion-detail-time-title">감정 발생 시각</h2>
                <p>나중에 기록했다면 실제로 감정을 느낀 시각으로 변경해 주세요.</p>
              </div>
              <form onSubmit={handleOccurredAtUpdate}>
                <label htmlFor="emotion-detail-occurred-at">
                  <span>날짜와 시간</span>
                  <input
                    id="emotion-detail-occurred-at"
                    type="datetime-local"
                    value={occurredAtInput}
                    max={getCurrentDateTimeLocalValue()}
                    step="60"
                    disabled={isUpdatingOccurredAt || isDeleting}
                    aria-invalid={isOccurredAtError}
                    aria-describedby={occurredAtMessage
                      ? 'emotion-detail-time-message'
                      : undefined}
                    onChange={(event) => {
                      setOccurredAtInput(event.target.value)
                      setOccurredAtMessage('')
                      setIsOccurredAtError(false)
                    }}
                  />
                </label>
                <button
                  type="submit"
                  disabled={isUpdatingOccurredAt || isDeleting}
                >
                  {isUpdatingOccurredAt ? '수정 중' : '시각 수정하기'}
                </button>
              </form>
              {occurredAtMessage && (
                <p
                  id="emotion-detail-time-message"
                  className={isOccurredAtError ? 'is-error' : 'is-success'}
                  role={isOccurredAtError ? 'alert' : 'status'}
                >
                  {occurredAtMessage}
                </p>
              )}
            </section>

            <section className="emotion-detail-section" aria-labelledby="emotion-detail-raw-title">
              <h2 id="emotion-detail-raw-title">기록한 마음</h2>
              <p>{getDisplayValue(record.rawText, '작성한 내용이 없습니다.')}</p>
            </section>

            <dl className="emotion-detail-list">
              <div>
                <dt>기록 상태</dt>
                <dd>
                  {completionStatusLabels[record.completionStatus]
                    ?? getDisplayValue(record.completionStatus, '상태 확인 전')}
                </dd>
              </div>
              <div>
                <dt>기록 시간대</dt>
                <dd>{timeBucketLabels[record.timeBucket] ?? getDisplayValue(record.timeBucket)}</dd>
              </div>
              <div>
                <dt>상황</dt>
                <dd>{getDisplayValue(record.situationText)}</dd>
              </div>
              <div>
                <dt>자동으로 떠오른 생각</dt>
                <dd>{getDisplayValue(record.automaticThought)}</dd>
              </div>
              <div>
                <dt>함께 느낀 감정</dt>
                <dd>{secondaryEmotionText}</dd>
              </div>
              <div>
                <dt>관련된 사람</dt>
                <dd>{getDisplayValue(record.relatedPersonType)}</dd>
              </div>
              <div>
                <dt>해석</dt>
                <dd>{getDisplayValue(record.details?.interpretation)}</dd>
              </div>
              <div>
                <dt>신체 반응</dt>
                <dd>{getDisplayValue(record.details?.bodyReaction)}</dd>
              </div>
              <div>
                <dt>행동</dt>
                <dd>{getDisplayValue(record.details?.behavior)}</dd>
              </div>
            </dl>

            {/* 감정 기록과 연결된 CBT 성찰 데이터를 함께 삭제하는 위험 작업 영역 배치. */}
            <section
              className="emotion-detail-delete"
              aria-labelledby="emotion-detail-delete-title"
            >
              <div>
                <h2 id="emotion-detail-delete-title">감정 기록 삭제</h2>
                <p>더 이상 보관하지 않을 감정 기록을 삭제할 수 있습니다.</p>
              </div>

              {!isDeleteConfirmOpen ? (
                <button
                  className="emotion-detail-delete-open"
                  type="button"
                  onClick={() => {
                    setIsDeleteConfirmOpen(true)
                    setDeleteError('')
                  }}
                >
                  기록 삭제하기
                </button>
              ) : (
                /* 연결된 데이터 삭제 범위를 알리고 최종 선택을 받는 확인 영역 표시. */
                <div className="emotion-detail-delete-confirm" role="alert">
                  <strong>정말 삭제하시겠습니까?</strong>
                  <p>
                    이 기록과 연결된 CBT 성찰 데이터도 함께 삭제되며,
                    삭제한 내용은 복구할 수 없습니다.
                  </p>
                  <div>
                    <button
                      type="button"
                      onClick={() => {
                        setIsDeleteConfirmOpen(false)
                        setDeleteError('')
                      }}
                      disabled={isDeleting}
                    >
                      취소
                    </button>
                    <button
                      className="emotion-detail-delete-confirm-button"
                      type="button"
                      onClick={handleEmotionRecordDelete}
                      disabled={isDeleting}
                    >
                      {isDeleting ? '삭제 중' : '삭제 확인'}
                    </button>
                  </div>
                </div>
              )}

              {deleteError && (
                <p className="emotion-detail-delete-error" role="alert">
                  {deleteError}
                </p>
              )}
            </section>
          </div>
        ) : null}
      </section>
    </main>
  )
}

export default EmotionRecordDetail
