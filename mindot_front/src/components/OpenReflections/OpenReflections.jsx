import { useEffect, useState } from 'react'
import {
  getOpenReflectionSessions,
  getReflectionSessionDetail,
} from '../../utils/reflections/reflectionsApi.js'
import './OpenReflections.css'

// 진행 중 CBT 성찰 API 오류 상태를 사용자 안내 문구로 변환.
const getOpenReflectionsErrorMessage = (error, fallbackMessage) => {
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

// 성찰 세션 생성 시각을 한국어 날짜와 시간 형식으로 변환.
const formatReflectionDate = (createdAt) => new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
}).format(new Date(createdAt))

// 백엔드 성찰 진행 단계를 사용자에게 표시할 문구로 변환.
const getReflectionStepLabel = (currentStep) => (
  currentStep === 'CONFIRM_REQUIRED'
    ? '최종 결과 확인 단계'
    : 'AI CBT 대화 진행 중'
)

// 진행 중 CBT 목록과 선택한 세션의 질문·답변 상세를 제공하는 컴포넌트 정의.
function OpenReflections({ onResume }) {
  // 백엔드에서 조회한 OPEN CBT 성찰 세션 목록 상태 설정.
  const [openSessions, setOpenSessions] = useState([])
  // OPEN 성찰 목록 조회 진행 여부 상태 설정.
  const [isLoading, setIsLoading] = useState(true)
  // OPEN 성찰 목록 조회 실패 안내 문구 상태 설정.
  const [loadError, setLoadError] = useState('')
  // OPEN 성찰 목록 재조회 횟수 상태 설정.
  const [reloadCount, setReloadCount] = useState(0)
  // 사용자가 상세 조회를 선택한 성찰 세션 식별자 상태 설정.
  const [selectedSessionId, setSelectedSessionId] = useState(null)
  // 선택한 성찰 세션의 질문과 답변 상세 상태 설정.
  const [sessionDetail, setSessionDetail] = useState(null)
  // 성찰 세션 상세 조회 진행 여부 상태 설정.
  const [isDetailLoading, setIsDetailLoading] = useState(false)
  // 성찰 세션 상세 조회 실패 안내 문구 상태 설정.
  const [detailError, setDetailError] = useState('')

  // 화면 진입과 재조회 시 진행 중 CBT 성찰 목록 요청.
  useEffect(() => {
    let isActive = true

    const loadOpenSessions = async () => {
      setIsLoading(true)
      setLoadError('')

      try {
        const sessions = await getOpenReflectionSessions()

        if (isActive) {
          setOpenSessions(Array.isArray(sessions) ? sessions : [])
          setSelectedSessionId(null)
          setSessionDetail(null)
          setDetailError('')
        }
      } catch (error) {
        if (isActive) {
          setOpenSessions([])
          setLoadError(getOpenReflectionsErrorMessage(
            error,
            '진행 중인 CBT 성찰을 불러오지 못했습니다.',
          ))
        }
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadOpenSessions()

    return () => {
      isActive = false
    }
  }, [reloadCount])

  // 목록에서 선택한 OPEN CBT 성찰의 질문과 답변 상세 조회 처리.
  const handleSessionSelect = async (sessionId) => {
    if (isDetailLoading) return

    setSelectedSessionId(sessionId)
    setSessionDetail(null)
    setDetailError('')
    setIsDetailLoading(true)

    try {
      const detail = await getReflectionSessionDetail(sessionId)

      setSessionDetail(detail)
    } catch (error) {
      setDetailError(getOpenReflectionsErrorMessage(
        error,
        '선택한 CBT 성찰의 상세 내용을 불러오지 못했습니다.',
      ))
    } finally {
      setIsDetailLoading(false)
    }
  }

  // 선택한 OPEN 성찰의 목록 정보와 상세 이력을 CBT 화면 이동 데이터로 전달.
  const handleReflectionResume = () => {
    if (!sessionDetail) return

    const selectedSession = openSessions.find(
      (session) => session.sessionId === selectedSessionId,
    )

    onResume({
      ...sessionDetail,
      emotionRecordId: selectedSession?.emotionRecordId ?? null,
    })
  }

  // OPEN CBT 성찰 목록과 선택 상세 화면 반환.
  return (
    <section
      className="open-reflections"
      aria-labelledby="open-reflections-title"
    >
      <header className="open-reflections-heading">
        <div>
          <h2 id="open-reflections-title">진행 중인 CBT 성찰</h2>
          <p>잠시 멈춘 성찰의 진행 단계와 대화 내용을 확인할 수 있습니다.</p>
        </div>
        <span>
          {isLoading ? '조회 중' : `${openSessions.length}개`}
        </span>
      </header>

      {isLoading ? (
        <p className="open-reflections-status" aria-live="polite">
          진행 중인 성찰을 불러오는 중입니다.
        </p>
      ) : loadError ? (
        <div className="open-reflections-status" role="alert">
          <p>{loadError}</p>
          <button
            type="button"
            onClick={() => setReloadCount((currentCount) => currentCount + 1)}
          >
            다시 불러오기
          </button>
        </div>
      ) : openSessions.length === 0 ? (
        <p className="open-reflections-status">
          현재 진행 중인 CBT 성찰이 없습니다.
        </p>
      ) : (
        <div className="open-reflections-list">
          {openSessions.map((session) => (
            <button
              className={selectedSessionId === session.sessionId ? 'is-selected' : ''}
              type="button"
              key={session.sessionId}
              onClick={() => handleSessionSelect(session.sessionId)}
              aria-expanded={selectedSessionId === session.sessionId}
              aria-controls="open-reflection-detail"
            >
              <span>
                <strong>{getReflectionStepLabel(session.currentStep)}</strong>
                <time dateTime={session.createdAt}>
                  {formatReflectionDate(session.createdAt)}
                </time>
              </span>
              <span>{session.rawText || '감정 기록 원문이 없습니다.'}</span>
            </button>
          ))}
        </div>
      )}

      {selectedSessionId && (
        <section
          className="open-reflection-detail"
          id="open-reflection-detail"
          aria-labelledby="open-reflection-detail-title"
        >
          <h3 id="open-reflection-detail-title">성찰 상세</h3>
          {isDetailLoading ? (
            <p className="open-reflections-status" aria-live="polite">
              질문과 답변을 불러오는 중입니다.
            </p>
          ) : detailError ? (
            <p className="open-reflections-error" role="alert">
              {detailError}
            </p>
          ) : sessionDetail ? (
            <>
              <p className="open-reflection-current-step">
                <strong>현재 상태</strong>
                <span>{getReflectionStepLabel(sessionDetail.currentStep)}</span>
              </p>
              <div className="open-reflection-questions">
                {(sessionDetail.questionAnswers ?? []).map((questionAnswer, index) => (
                  <section key={questionAnswer.questionCode ?? `question-${index}`}>
                    <strong>질문 {index + 1}</strong>
                    <p>{questionAnswer.question || '질문 내용이 없습니다.'}</p>
                    <span>내 답변</span>
                    <p>
                      {questionAnswer.answer || '아직 답변하지 않은 질문입니다.'}
                    </p>
                  </section>
                ))}
              </div>
              {/* 현재 단계에 맞는 CBT 재진입 버튼 문구 설정. */}
              <button
                className="open-reflection-resume-button"
                type="button"
                onClick={handleReflectionResume}
              >
                {sessionDetail.currentStep === 'CONFIRM_REQUIRED'
                  ? '최종 결과 확인 이어하기'
                  : 'CBT 성찰 이어하기'}
              </button>
            </>
          ) : null}
        </section>
      )}
    </section>
  )
}

export default OpenReflections
