import { useEffect, useMemo, useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import {
  checkAdminAccess,
  getAdminSafetyEventDetail,
  getAdminSafetyEvents,
  getAdminUsers,
} from '../../utils/admin/adminApi.js'
import './Admin.css'

const ADMIN_PAGE_SIZE = 20

// 관리자 권한 확인 실패 응답을 화면 상태와 안내 문구로 변환.
const getAdminAccessError = (error) => {
  if (error.response?.status === 403) {
    return {
      status: 'denied',
      message: '관리자 권한이 있는 계정만 접근할 수 있습니다.',
    }
  }

  if (error.response?.status === 401) {
    return {
      status: 'unauthorized',
      message: '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.',
    }
  }

  return {
    status: 'error',
    message: '관리자 권한을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  }
}

// 관리자 데이터 API 오류 응답에서 사용자 안내 문구 추출.
const getAdminDataErrorMessage = (error, fallbackMessage) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 서버 실행 상태를 확인해 주세요.'
  }

  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }

  if (error.response.status === 403) {
    return '관리자 권한이 없어 정보를 불러올 수 없습니다.'
  }

  return error.response.data?.message ?? fallbackMessage
}

// 서버 일시 값을 관리자 화면용 한국어 날짜와 시간으로 변환.
const formatAdminDate = (dateValue) => {
  if (!dateValue) return '정보 없음'

  const date = new Date(dateValue)
  if (Number.isNaN(date.getTime())) return '정보 없음'

  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

// 서버 회원 상태 코드를 관리자 확인용 한국어 문구로 변환.
const accountStatusLabels = {
  ACTIVE: '활성',
  SUSPENDED: '이용 정지',
  WITHDRAWN: '탈퇴',
}

// 서버 회원 권한 코드를 관리자 확인용 한국어 문구로 변환.
const userRoleLabels = {
  ROLE_USER: '일반 회원',
  ROLE_ADMIN: '관리자',
}

// 서버 위험 수준 코드를 안전 신호 확인용 한국어 문구로 변환.
const riskLevelLabels = {
  REVIEW: '검토 필요',
  CRISIS: '즉시 확인',
}

// 서버 안전 조치 코드를 관리자 확인용 한국어 문구로 변환.
const actionCodeLabels = {
  SHOW_REVIEW_NOTICE: '추가 확인 안내',
  SHOW_CRISIS_NOTICE: '위기 안내',
}

// 관리자 회원 목록과 안전 신호 목록 및 상세 조회 화면 정의.
function Admin({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onDailyCare,
  onHome,
}) {
  // 관리자 전용 회원 API 확인 결과를 보관하는 접근 상태 설정.
  const [accessStatus, setAccessStatus] = useState('checking')
  // 권한 확인 실패 시 사용자에게 표시할 안내 문구 상태 설정.
  const [accessMessage, setAccessMessage] = useState('')
  // 관리자 회원 목록 데이터 상태 설정.
  const [users, setUsers] = useState([])
  const [userPage, setUserPage] = useState(0)
  const [userTotalPages, setUserTotalPages] = useState(0)
  const [userTotalElements, setUserTotalElements] = useState(0)
  const [userListStatus, setUserListStatus] = useState('idle')
  const [userListMessage, setUserListMessage] = useState('')
  // 관리자 안전 신호 목록 데이터 상태 설정.
  const [safetyEvents, setSafetyEvents] = useState([])
  const [safetyPage, setSafetyPage] = useState(0)
  const [safetyTotalPages, setSafetyTotalPages] = useState(0)
  const [safetyTotalElements, setSafetyTotalElements] = useState(0)
  // 안전 신호 목록의 로딩·성공·실패 상태 설정.
  const [safetyListStatus, setSafetyListStatus] = useState('idle')
  // 안전 신호 목록 조회 실패 안내 문구 상태 설정.
  const [safetyListMessage, setSafetyListMessage] = useState('')
  // 상세 조회 대상으로 선택한 안전 신호 식별자 상태 설정.
  const [selectedSafetyEventId, setSelectedSafetyEventId] = useState(null)
  // 선택한 안전 신호 상세 데이터 상태 설정.
  const [safetyEventDetail, setSafetyEventDetail] = useState(null)
  // 안전 신호 상세의 로딩·성공·실패 상태 설정.
  const [safetyDetailStatus, setSafetyDetailStatus] = useState('idle')
  // 안전 신호 상세 조회 실패 안내 문구 상태 설정.
  const [safetyDetailMessage, setSafetyDetailMessage] = useState('')
  // 권한 확인과 두 목록의 재조회 횟수를 각각 관리.
  const [accessRetryCount, setAccessRetryCount] = useState(0)
  const [userRetryCount, setUserRetryCount] = useState(0)
  const [safetyRetryCount, setSafetyRetryCount] = useState(0)
  // 선택한 안전 신호 상세 재조회 횟수 상태 설정.
  const [detailRetryCount, setDetailRetryCount] = useState(0)

  // 선택된 안전 신호의 목록 정보를 상세 정보와 함께 표시하기 위한 조회 처리.
  const selectedSafetyEvent = useMemo(
    () => safetyEvents.find(
      (safetyEvent) => safetyEvent.safetyEventId === selectedSafetyEventId,
    ) ?? null,
    [safetyEvents, selectedSafetyEventId],
  )

  // 소량의 회원 목록 요청으로 관리자 권한을 확인.
  useEffect(() => {
    let isActive = true

    const loadAdminAccess = async () => {
      setAccessStatus('checking')
      setAccessMessage('')

      try {
        await checkAdminAccess()
        if (!isActive) return

        setAccessStatus('allowed')
      } catch (error) {
        if (!isActive) return

        const accessError = getAdminAccessError(error)
        setAccessStatus(accessError.status)
        setAccessMessage(accessError.message)
      }
    }

    loadAdminAccess()

    return () => {
      isActive = false
    }
  }, [accessRetryCount])

  // 회원 목록의 현재 페이지만 조회하고 전체 회원 수를 보관.
  useEffect(() => {
    if (accessStatus !== 'allowed') return undefined

    let isActive = true

    const loadUsers = async () => {
      setUserListStatus('loading')
      setUserListMessage('')

      try {
        const result = await getAdminUsers(userPage, ADMIN_PAGE_SIZE)
        if (!isActive) return

        if (result.totalPages > 0 && userPage >= result.totalPages) {
          setUserPage(result.totalPages - 1)
          return
        }

        setUsers(result.content ?? [])
        setUserTotalPages(result.totalPages ?? 0)
        setUserTotalElements(result.totalElements ?? 0)
        setUserListStatus('success')
      } catch (error) {
        if (!isActive) return

        setUserListStatus('error')
        setUserListMessage(getAdminDataErrorMessage(
          error,
          '회원 목록을 불러오지 못했습니다.',
        ))
      }
    }

    loadUsers()
    return () => { isActive = false }
  }, [accessStatus, userPage, userRetryCount])

  // 안전 신호 목록의 현재 페이지만 조회하고 선택 항목을 갱신.
  useEffect(() => {
    if (accessStatus !== 'allowed') return undefined

    let isActive = true

    const loadSafetyEvents = async () => {
      setSafetyListStatus('loading')
      setSafetyListMessage('')
      setSelectedSafetyEventId(null)
      setSafetyEventDetail(null)

      try {
        const result = await getAdminSafetyEvents(safetyPage, ADMIN_PAGE_SIZE)
        if (!isActive) return

        if (result.totalPages > 0 && safetyPage >= result.totalPages) {
          setSafetyPage(result.totalPages - 1)
          return
        }

        const content = result.content ?? []
        setSafetyEvents(content)
        setSafetyTotalPages(result.totalPages ?? 0)
        setSafetyTotalElements(result.totalElements ?? 0)
        setSelectedSafetyEventId(content[0]?.safetyEventId ?? null)
        setSafetyListStatus('success')
      } catch (error) {
        if (!isActive) return

        setSafetyListStatus('error')
        setSafetyListMessage(getAdminDataErrorMessage(
          error,
          '안전 신호 목록을 불러오지 못했습니다.',
        ))
      }
    }

    loadSafetyEvents()
    return () => { isActive = false }
  }, [accessStatus, safetyPage, safetyRetryCount])

  // 안전 신호 선택과 재시도 시 식별자를 이용한 상세 API 조회 처리.
  useEffect(() => {
    if (!selectedSafetyEventId) {
      return undefined
    }

    let isActive = true

    const loadSafetyEventDetail = async () => {
      setSafetyEventDetail(null)
      setSafetyDetailStatus('loading')
      setSafetyDetailMessage('')

      try {
        const detail = await getAdminSafetyEventDetail(selectedSafetyEventId)
        if (!isActive) return

        setSafetyEventDetail(detail)
        setSafetyDetailStatus('success')
      } catch (error) {
        if (!isActive) return

        setSafetyDetailStatus('error')
        setSafetyDetailMessage(getAdminDataErrorMessage(
          error,
          '선택한 안전 신호 상세 정보를 불러오지 못했습니다.',
        ))
      }
    }

    loadSafetyEventDetail()

    return () => {
      isActive = false
    }
  }, [detailRetryCount, selectedSafetyEventId])

  // 관리자 권한 상태와 API 조회 결과에 맞는 관리자 화면 반환.
  return (
    <div className="admin-page">
      {/* 다른 서비스 화면과 동일한 크기와 기능의 공통 네비게이션 배치. */}
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

      <main className="admin-main" aria-busy={accessStatus === 'checking'}>
        {accessStatus === 'checking' ? (
          /* 권한 확인이 끝나기 전에 관리자 정보를 숨기는 대기 상태 영역. */
          <section className="admin-access-status" aria-live="polite">
            <h1>관리자 권한 확인</h1>
            <p>현재 계정의 관리자 권한을 확인하고 있습니다.</p>
          </section>
        ) : accessStatus === 'allowed' ? (
          <>
            {/* 관리자 권한 확인 이후 화면 목적을 알려 주는 상단 제목 영역. */}
            <header className="admin-heading">
              <h1>관리자</h1>
              <p>회원 정보와 서비스 안전 신호를 확인하는 공간입니다.</p>
            </header>

            {/* 회원 목록 API 응답을 가입일과 상태 및 안전 신호 횟수로 표시. */}
            <section className="admin-section" aria-labelledby="admin-users-title">
              <div className="admin-section-heading">
                <div>
                  <h2 id="admin-users-title">회원 목록</h2>
                  <p>가입한 회원의 기본 정보와 안전 신호 발생 횟수를 확인합니다.</p>
                </div>
                <span className="admin-count">전체 {userTotalElements}명</span>
              </div>

              {userListStatus === 'loading' ? (
                <p className="admin-loading-message" aria-live="polite">
                  회원 목록을 불러오고 있습니다.
                </p>
              ) : userListStatus === 'error' ? (
                <div className="admin-inline-error" role="alert">
                  <p>{userListMessage}</p>
                  <button
                    type="button"
                    onClick={() => setUserRetryCount((count) => count + 1)}
                  >
                    목록 다시 불러오기
                  </button>
                </div>
              ) : users.length > 0 ? (
                <ul className="admin-data-list admin-user-list">
                  {users.map((user) => (
                    <li key={user.userId} className="admin-user-row">
                      <div className="admin-user-identity">
                        <strong>{user.displayName || '이름 없음'}</strong>
                        <span>{user.email || '이메일 없음'}</span>
                      </div>
                      <div className="admin-user-meta">
                        <span>{accountStatusLabels[user.status] ?? user.status}</span>
                        <span>{userRoleLabels[user.userRole] ?? user.userRole}</span>
                        <span>안전 신호 {user.safetyEventCount ?? 0}회</span>
                        <time dateTime={user.createdAt}>
                          가입 {formatAdminDate(user.createdAt)}
                        </time>
                      </div>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="admin-empty-message">가입한 회원이 없습니다.</p>
              )}

              {userListStatus === 'success' && userTotalPages > 1 && (
                <nav className="admin-pagination" aria-label="회원 목록 페이지">
                  <button
                    type="button"
                    disabled={userPage === 0}
                    onClick={() => setUserPage((page) => page - 1)}
                  >
                    이전
                  </button>
                  <span>{userPage + 1} / {userTotalPages}</span>
                  <button
                    type="button"
                    disabled={userPage + 1 >= userTotalPages}
                    onClick={() => setUserPage((page) => page + 1)}
                  >
                    다음
                  </button>
                </nav>
              )}
            </section>

            {/* 안전 신호 목록 선택과 연결된 감정 기록 상세 조회 영역 배치. */}
            <section className="admin-section" aria-labelledby="admin-safety-title">
              <div className="admin-section-heading">
                <div>
                  <h2 id="admin-safety-title">안전 신호 목록</h2>
                  <p>감지된 안전 신호를 선택해 연결된 감정 기록을 확인합니다.</p>
                </div>
                <span className="admin-count">전체 {safetyTotalElements}건</span>
              </div>

              {safetyListStatus === 'loading' ? (
                <p className="admin-loading-message" aria-live="polite">
                  안전 신호 목록을 불러오고 있습니다.
                </p>
              ) : safetyListStatus === 'error' ? (
                <div className="admin-inline-error" role="alert">
                  <p>{safetyListMessage}</p>
                  <button
                    type="button"
                    onClick={() => setSafetyRetryCount((count) => count + 1)}
                  >
                    목록 다시 불러오기
                  </button>
                </div>
              ) : safetyEvents.length > 0 ? (
                <div className="admin-safety-layout">
                  <ul className="admin-data-list admin-safety-list">
                    {safetyEvents.map((safetyEvent) => {
                      const isSelected = selectedSafetyEventId
                        === safetyEvent.safetyEventId

                      return (
                        <li key={safetyEvent.safetyEventId}>
                          <button
                            className={`admin-safety-row${isSelected ? ' is-selected' : ''}`}
                            type="button"
                            aria-pressed={isSelected}
                            onClick={() => setSelectedSafetyEventId(
                              safetyEvent.safetyEventId,
                            )}
                          >
                            <span className={`admin-risk-level admin-risk-${safetyEvent.riskLevel?.toLowerCase()}`}>
                              {riskLevelLabels[safetyEvent.riskLevel]
                                ?? safetyEvent.riskLevel
                                ?? '수준 미확인'}
                            </span>
                            <strong>{safetyEvent.displayName || '이름 없음'}</strong>
                            <span>{safetyEvent.email || '이메일 없음'}</span>
                            <time dateTime={safetyEvent.createdAt}>
                              {formatAdminDate(safetyEvent.createdAt)}
                            </time>
                          </button>
                        </li>
                      )
                    })}
                  </ul>

                  <section
                    className="admin-safety-detail"
                    aria-labelledby="admin-safety-detail-title"
                    aria-busy={safetyDetailStatus === 'loading'}
                  >
                    <h3 id="admin-safety-detail-title">안전 신호 상세</h3>

                    {safetyDetailStatus === 'loading' ? (
                      <p className="admin-loading-message" aria-live="polite">
                        상세 정보를 불러오고 있습니다.
                      </p>
                    ) : safetyDetailStatus === 'error' ? (
                      <div className="admin-inline-error" role="alert">
                        <p>{safetyDetailMessage}</p>
                        <button
                          type="button"
                          onClick={() => setDetailRetryCount(
                            (currentCount) => currentCount + 1,
                          )}
                        >
                          상세 다시 불러오기
                        </button>
                      </div>
                    ) : safetyEventDetail ? (
                      <>
                        <dl className="admin-detail-list">
                          <div>
                            <dt>회원</dt>
                            <dd>
                              {safetyEventDetail.displayName || '이름 없음'}
                              {' · '}
                              {safetyEventDetail.email || '이메일 없음'}
                            </dd>
                          </div>
                          <div>
                            <dt>위험 수준</dt>
                            <dd>
                              {riskLevelLabels[safetyEventDetail.riskLevel]
                                ?? safetyEventDetail.riskLevel
                                ?? '정보 없음'}
                            </dd>
                          </div>
                          <div>
                            <dt>감지 사유</dt>
                            <dd>{safetyEventDetail.reasonCode || '정보 없음'}</dd>
                          </div>
                          <div>
                            <dt>안내 조치</dt>
                            <dd>
                              {actionCodeLabels[safetyEventDetail.actionCode]
                                ?? safetyEventDetail.actionCode
                                ?? '정보 없음'}
                            </dd>
                          </div>
                          <div>
                            <dt>감정 발생 시각</dt>
                            <dd>{formatAdminDate(safetyEventDetail.occurredAt)}</dd>
                          </div>
                          <div>
                            <dt>안내 표시</dt>
                            <dd>
                              {selectedSafetyEvent?.noticeShownAt
                                ? formatAdminDate(selectedSafetyEvent.noticeShownAt)
                                : '표시 기록 없음'}
                            </dd>
                          </div>
                        </dl>

                        <div className="admin-record-content">
                          <h4>연결된 감정 기록</h4>
                          <p>{safetyEventDetail.rawText || '기록 내용이 없습니다.'}</p>
                          <span>
                            감정 기록 번호 {safetyEventDetail.emotionRecordId}
                          </span>
                        </div>
                      </>
                    ) : (
                      <p className="admin-empty-message">
                        확인할 안전 신호를 선택해 주세요.
                      </p>
                    )}
                  </section>
                </div>
              ) : (
                <p className="admin-empty-message">발생한 안전 신호가 없습니다.</p>
              )}

              {safetyListStatus === 'success' && safetyTotalPages > 1 && (
                <nav className="admin-pagination" aria-label="안전 신호 목록 페이지">
                  <button
                    type="button"
                    disabled={safetyPage === 0}
                    onClick={() => setSafetyPage((page) => page - 1)}
                  >
                    이전
                  </button>
                  <span>{safetyPage + 1} / {safetyTotalPages}</span>
                  <button
                    type="button"
                    disabled={safetyPage + 1 >= safetyTotalPages}
                    onClick={() => setSafetyPage((page) => page + 1)}
                  >
                    다음
                  </button>
                </nav>
              )}
            </section>
          </>
        ) : (
          /* 권한 없음과 인증 만료 및 확인 실패 상태를 구분하는 안내 영역. */
          <section className="admin-access-status" role="alert">
            <h1>관리자 화면에 접근할 수 없습니다</h1>
            <p>{accessMessage}</p>
            <div className="admin-access-actions">
              {accessStatus === 'unauthorized' ? (
                <button type="button" onClick={onLogin}>로그인하기</button>
              ) : accessStatus === 'error' ? (
                <button
                  type="button"
                  onClick={() => setAccessRetryCount((count) => count + 1)}
                >
                  다시 확인하기
                </button>
              ) : null}
              <button type="button" onClick={onHome}>메인으로 돌아가기</button>
            </div>
          </section>
        )}
      </main>
    </div>
  )
}

export default Admin
