import { useEffect, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import { getEmotionRecordDetail } from '../../utils/records/recordsApi.js'
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

        if (isActive) setRecord(detail)
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
          </div>
        ) : null}
      </section>
    </main>
  )
}

export default EmotionRecordDetail
