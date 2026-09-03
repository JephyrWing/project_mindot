import { useEffect, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import { getReflectionSessionDetail } from '../../utils/reflections/reflectionsApi.js'
import './CompletedReflection.css'

// 완료 CBT 결과 상세 조회 실패 상태에 맞는 사용자 안내 문구 반환.
const getReflectionErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (error.response.status === 403) {
    return '이 CBT 성찰 결과를 확인할 권한이 없습니다.'
  }
  if (error.response.status === 404) {
    return '완료된 CBT 성찰 결과를 찾을 수 없습니다.'
  }

  return 'CBT 성찰 결과를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 비어 있는 CBT 결과 항목에 공통 안내 문구 표시.
const getResultText = (value) => (
  value === null || value === undefined || value === ''
    ? '작성된 내용이 없습니다.'
    : value
)

// 수치형 CBT 결과를 각 항목의 최댓값과 함께 표시.
const getScoreText = (value, maximum) => (
  Number.isFinite(value) ? `${value}/${maximum}` : '기록 없음'
)

// 완료된 CBT 성찰의 확정 결과를 조회하고 보여주는 화면 정의.
function CompletedReflection({
  sessionId,
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
  // 백엔드에서 조회한 완료 CBT 성찰 상세 상태 관리.
  const [reflection, setReflection] = useState(null)
  // 완료 CBT 성찰 상세 조회 진행 상태 관리.
  const [isLoading, setIsLoading] = useState(true)
  // 완료 CBT 성찰 상세 조회 실패 안내 상태 관리.
  const [loadError, setLoadError] = useState('')
  // 사용자가 상세 결과 재조회 버튼을 선택한 횟수 상태 관리.
  const [reloadCount, setReloadCount] = useState(0)

  // 화면 진입과 재조회 시 선택한 CBT 세션의 확정 결과 요청.
  useEffect(() => {
    let isActive = true

    const loadCompletedReflection = async () => {
      if (!sessionId) {
        setIsLoading(false)
        setLoadError('조회할 완료 CBT 성찰을 선택해 주세요.')
        return
      }

      setIsLoading(true)
      setLoadError('')

      try {
        const detail = await getReflectionSessionDetail(sessionId)

        if (!isActive) return

        if (detail.status !== 'COMPLETED' || !detail.outcome) {
          setReflection(null)
          setLoadError('아직 최종 확정되지 않은 CBT 성찰입니다.')
          return
        }

        setReflection(detail)
      } catch (error) {
        if (isActive) {
          setReflection(null)
          setLoadError(getReflectionErrorMessage(error))
        }
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadCompletedReflection()

    return () => {
      isActive = false
    }
  }, [reloadCount, sessionId])

  // 완료 CBT 결과와 조회 상태를 공통 네비게이션 아래에 표시.
  return (
    <main className="completed-reflection-page">
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

      <div className="completed-reflection-layout">
        <section
          className="completed-reflection-card"
          aria-labelledby="completed-reflection-title"
        >
          <BrandLogo className="completed-reflection-logo" onClick={onHome} />

          <header className="completed-reflection-heading">
            <div>
              <h1 id="completed-reflection-title">완료된 CBT 결과</h1>
              <p>성찰을 통해 정리하고 최종 확정한 생각의 변화를 확인합니다.</p>
            </div>
            {reflection && <span>성찰 완료</span>}
          </header>

          {isLoading ? (
            <div className="completed-reflection-state" role="status">
              <strong>CBT 결과를 불러오는 중입니다.</strong>
            </div>
          ) : loadError ? (
            <div className="completed-reflection-state" role="alert">
              <strong>{loadError}</strong>
              <div>
                <button type="button" onClick={onBack}>주간 리포트로 돌아가기</button>
                {sessionId && (
                  <button type="button" onClick={() => setReloadCount((count) => count + 1)}>
                    다시 불러오기
                  </button>
                )}
              </div>
            </div>
          ) : (
            <>
              {/* 사용자가 확정한 근거와 대안적 사고를 읽기 전용 결과로 표시. */}
              <div className="completed-reflection-text-results">
                <section>
                  <h2>처음 생각을 뒷받침하는 근거</h2>
                  <p>{getResultText(reflection.outcome.evidenceForText)}</p>
                </section>
                <section>
                  <h2>처음 생각과 다른 근거</h2>
                  <p>{getResultText(reflection.outcome.evidenceAgainstText)}</p>
                </section>
                <section>
                  <h2>대안적 사고</h2>
                  <p>{getResultText(reflection.outcome.alternativeThoughtText)}</p>
                </section>
              </div>

              {/* 성찰 전후의 확신도와 최종 평가 수치를 한눈에 비교하는 영역 배치. */}
              <section
                className="completed-reflection-scores"
                aria-labelledby="completed-reflection-scores-title"
              >
                <h2 id="completed-reflection-scores-title">성찰 변화</h2>
                <dl>
                  <div>
                    <dt>성찰 전 생각 확신도</dt>
                    <dd>{getScoreText(reflection.outcome.beforeBeliefStrength, 100)}</dd>
                  </div>
                  <div>
                    <dt>성찰 후 생각 확신도</dt>
                    <dd>{getScoreText(reflection.outcome.afterBeliefStrength, 100)}</dd>
                  </div>
                  <div>
                    <dt>최종 감정 강도</dt>
                    <dd>{getScoreText(reflection.outcome.finalEmotionIntensity, 10)}</dd>
                  </div>
                  <div>
                    <dt>도움 점수</dt>
                    <dd>{getScoreText(reflection.outcome.helpfulnessScore, 5)}</dd>
                  </div>
                </dl>
              </section>

              <button
                className="completed-reflection-back-button"
                type="button"
                onClick={onBack}
              >
                주간 리포트로 돌아가기
              </button>
            </>
          )}
        </section>
      </div>
    </main>
  )
}

export default CompletedReflection
