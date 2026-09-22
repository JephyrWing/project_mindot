// 최종 확인한 CBT 성찰 결과를 감정 기록 목록에서 다시 여는 컴포넌트
import { useEffect, useState } from 'react'
import { getCompletedReflectionSessions } from '../../utils/reflections/reflectionsApi.js'
import '../OpenReflections/OpenReflections.css'
import './CompletedReflections.css'

const formatCompletedDate = (completedAt) => new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
}).format(new Date(completedAt))

const formatCompletedPreviewDate = (completedAt) => new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
}).format(new Date(completedAt))

const getCompletedReflectionsErrorMessage = (error) => {
  if (!error.response) return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  if (error.response.status === 401) return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  return error.response.data?.message ?? '완료한 CBT 성찰을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

function CompletedReflections({ onOpen, onViewAll, listMode = false }) {
  const [sessions, setSessions] = useState([])
  const [isLoading, setIsLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [reloadCount, setReloadCount] = useState(0)
  const [currentPage, setCurrentPage] = useState(0)
  const [pageInfo, setPageInfo] = useState({ totalPages: 0, totalElements: 0 })

  useEffect(() => {
    let isActive = true

    const loadCompletedSessions = async () => {
      setIsLoading(true)
      setLoadError('')
      try {
        const response = await getCompletedReflectionSessions({
          page: listMode ? currentPage : 0,
          size: listMode ? 10 : 3,
        })
        if (isActive) {
          setSessions(Array.isArray(response.content) ? response.content : [])
          setPageInfo({
            totalPages: response.totalPages ?? 0,
            totalElements: response.totalElements ?? 0,
          })
        }
      } catch (error) {
        if (isActive) {
          setSessions([])
          setLoadError(getCompletedReflectionsErrorMessage(error))
        }
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadCompletedSessions()
    return () => { isActive = false }
  }, [currentPage, listMode, reloadCount])

  return (
    <section className="open-reflections completed-reflections" aria-labelledby="completed-reflections-title">
      <header className="open-reflections-heading">
        <div>
          <h2 id="completed-reflections-title">완료한 CBT 성찰</h2>
          <p>최종 확인한 성찰 결과를 다시 확인할 수 있습니다.</p>
        </div>
        <span>{isLoading ? '조회 중' : `${pageInfo.totalElements}개`}</span>
      </header>

      {isLoading ? (
        <p className="open-reflections-status" aria-live="polite">완료한 성찰을 불러오는 중입니다.</p>
      ) : loadError ? (
        <div className="open-reflections-status" role="alert">
          <p>{loadError}</p>
          <button type="button" onClick={() => setReloadCount((count) => count + 1)}>다시 불러오기</button>
        </div>
      ) : pageInfo.totalElements === 0 ? (
        <p className="open-reflections-status">아직 완료한 CBT 성찰이 없습니다.</p>
      ) : !listMode ? (
        <>
          <div className="completed-reflections-preview" aria-label="최근 완료한 CBT 성찰">
            {sessions.map((session) => (
              <button type="button" key={session.sessionId} onClick={() => onOpen(session.sessionId)}>
                <time dateTime={session.completedAt}>{formatCompletedPreviewDate(session.completedAt)}</time>
                <span>성찰 후 생각 · {session.alternativeThoughtText || '저장된 생각이 없습니다.'}</span>
              </button>
            ))}
          </div>
          <button className="completed-reflections-view-button" type="button" onClick={onViewAll}>
            완료한 CBT 성찰 목록 보기
          </button>
        </>
      ) : (
        <>
          <div className="open-reflections-list">
            {sessions.map((session) => (
              <button type="button" key={session.sessionId} onClick={() => onOpen(session.sessionId)}>
                <span>
                  <strong>성찰 결과 보기</strong>
                  <time dateTime={session.completedAt}>{formatCompletedDate(session.completedAt)}</time>
                </span>
                <span>{session.alternativeThoughtText || session.rawText || '저장된 성찰 결과가 없습니다.'}</span>
              </button>
            ))}
          </div>
          {pageInfo.totalPages > 1 && (
            <nav className="open-reflections-pagination" aria-label="완료한 CBT 성찰 페이지">
              <button type="button" disabled={currentPage === 0} onClick={() => setCurrentPage((page) => page - 1)}>이전</button>
              <span aria-current="page">{currentPage + 1} / {pageInfo.totalPages}</span>
              <button type="button" disabled={currentPage + 1 >= pageInfo.totalPages} onClick={() => setCurrentPage((page) => page + 1)}>다음</button>
            </nav>
          )}
        </>
      )}
    </section>
  )
}

export default CompletedReflections
