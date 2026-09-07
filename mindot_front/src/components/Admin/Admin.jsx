import { useEffect, useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import { checkAdminAccess } from '../../utils/admin/adminApi.js'
import './Admin.css'

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

// 관리자 기능을 단계적으로 추가하기 위한 기본 화면 구조 정의.
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
  // 관리자 전용 API 확인 결과를 보관하는 접근 상태 설정.
  const [accessStatus, setAccessStatus] = useState('checking')
  // 권한 확인 실패 시 사용자에게 표시할 안내 문구 상태 설정.
  const [accessMessage, setAccessMessage] = useState('')
  // 관리자 권한 확인 재시도 횟수 상태 설정.
  const [retryCount, setRetryCount] = useState(0)

  // 화면 진입과 재시도 시 관리자 전용 API를 통한 실제 권한 확인 처리.
  useEffect(() => {
    let isActive = true

    const verifyAdminAccess = async () => {
      setAccessStatus('checking')
      setAccessMessage('')

      try {
        await checkAdminAccess()

        if (isActive) setAccessStatus('allowed')
      } catch (error) {
        if (!isActive) return

        const accessError = getAdminAccessError(error)
        setAccessStatus(accessError.status)
        setAccessMessage(accessError.message)
      }
    }

    verifyAdminAccess()

    return () => {
      isActive = false
    }
  }, [retryCount])

  // 관리자 권한 상태에 맞는 기본 관리자 화면 반환.
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

            {/* 추후 회원 목록 데이터를 표시할 기본 영역. */}
            <section className="admin-section" aria-labelledby="admin-users-title">
              <div>
                <h2 id="admin-users-title">회원 관리</h2>
                <p>가입한 회원의 기본 정보와 안전 신호 발생 횟수를 확인합니다.</p>
              </div>
              <span>API 연결 전</span>
            </section>

            {/* 추후 안전 신호 목록과 상세 데이터를 표시할 기본 영역. */}
            <section className="admin-section" aria-labelledby="admin-safety-title">
              <div>
                <h2 id="admin-safety-title">안전 신호 관리</h2>
                <p>감지된 안전 신호 목록과 연결된 감정 기록을 확인합니다.</p>
              </div>
              <span>API 연결 전</span>
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
                  onClick={() => setRetryCount((currentCount) => currentCount + 1)}
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
