import { useState } from 'react'
import './App.css'
import Main from './components/Main/Main.jsx'
import Login from './components/Login/Login.jsx'
import SignUp from './components/SignUp/SignUp.jsx'
import EmotionRecord from './components/EmotionRecord/EmotionRecord.jsx'
import EmotionHistory from './components/EmotionHistory/EmotionHistory.jsx'
import CBT from './components/CBT/CBT.jsx'
import WeeklyReport from './components/WeeklyReport/WeeklyReport.jsx'
import AppIntroModal from './components/AppIntroModal/AppIntroModal.jsx'
import Center from './components/Center/Center.jsx'
import NetworkStatus from './components/NetworkStatus/NetworkStatus.jsx'
import { logout } from './utils/auth/authApi.js'
import { getAccessToken } from './utils/auth/tokenStorage.js'

// 애플리케이션의 최상위 화면을 구성하는 루트 컴포넌트 정의.
function App() {
  // 현재 표시할 화면 상태 관리.
  const [currentPage, setCurrentPage] = useState('main')
  // 앱을 처음 열었을 때 서비스 안내창을 표시하기 위한 상태 관리.
  const [isIntroOpen, setIsIntroOpen] = useState(true)
  // 브라우저에 저장된 Access Token을 기준으로 로그인 여부 상태 관리.
  const [isAuthenticated, setIsAuthenticated] = useState(
    () => Boolean(getAccessToken()),
  )
  // 중복 로그아웃 요청을 방지하기 위한 진행 상태 관리.
  const [isLoggingOut, setIsLoggingOut] = useState(false)
  // CBT 성찰을 시작할 저장 완료 감정 기록 식별자 상태 관리.
  const [cbtEmotionRecordId, setCbtEmotionRecordId] = useState(null)
  // 각 화면의 로고 선택 시 새로고침 없이 메인페이지로 이동하는 처리.
  const moveToMain = () => setCurrentPage('main')
  // 로그인 성공 후 인증 상태 반영과 메인페이지 이동 처리.
  const handleLoginSuccess = () => {
    setIsAuthenticated(true)
    moveToMain()
  }
  // 로그아웃 API 호출 후 로컬 인증 상태 해제와 메인페이지 이동 처리.
  const handleLogout = async () => {
    if (isLoggingOut) return

    setIsLoggingOut(true)

    try {
      await logout()
    } catch {
      // 서버 요청 실패 시에도 authApi에서 삭제한 로컬 토큰 상태 유지.
    } finally {
      setIsAuthenticated(false)
      setIsLoggingOut(false)
      moveToMain()
    }
  }
  // 저장된 감정 기록 식별자를 보관하고 CBT 화면으로 이동하는 처리.
  const handleCbtOpen = (emotionRecordId) => {
    setCbtEmotionRecordId(emotionRecordId)
    setCurrentPage('cbt')
  }
  // 현재 화면 상태에 따라 렌더링할 페이지 컴포넌트 보관.
  let currentPageContent

  // 로그인 화면 선택 시 로그인 컴포넌트 렌더링.
  if (currentPage === 'login') {
    currentPageContent = (
      <Login
        onLoginSuccess={handleLoginSuccess}
        onSignUp={() => setCurrentPage('signup')}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'signup') {
    // 회원가입 화면 선택 시 회원가입 컴포넌트 렌더링.
    currentPageContent = (
      <SignUp
        onSignUpSuccess={() => setCurrentPage('login')}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'emotion-record') {
    // 감정 기록 화면 선택 시 감정 기록 컴포넌트 렌더링.
    currentPageContent = (
      <EmotionRecord
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => setCurrentPage('login')}
        onLogout={handleLogout}
        onSignUp={() => setCurrentPage('signup')}
        onEmotionHistory={() => setCurrentPage('emotion-history')}
        onCenter={() => setCurrentPage('center')}
        onCBT={handleCbtOpen}
        onWeeklyReport={() => setCurrentPage('weekly-report')}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'emotion-history') {
    // 감정 기록 목록 화면 선택 시 빈 목록 초안과 이동 기능 렌더링.
    currentPageContent = (
      <EmotionHistory
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => setCurrentPage('login')}
        onLogout={handleLogout}
        onSignUp={() => setCurrentPage('signup')}
        onEmotionHistory={() => setCurrentPage('emotion-history')}
        onEmotionRecord={() => setCurrentPage('emotion-record')}
        onCenter={() => setCurrentPage('center')}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'cbt') {
    // 감정 기록 저장 완료 후 선택한 CBT 성찰 화면 렌더링.
    currentPageContent = (
      <CBT
        emotionRecordId={cbtEmotionRecordId}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'weekly-report') {
    // 주간 리포트 화면 선택 시 간단한 리포트 초안 컴포넌트 렌더링.
    currentPageContent = (
      <WeeklyReport
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => setCurrentPage('login')}
        onLogout={handleLogout}
        onSignUp={() => setCurrentPage('signup')}
        onEmotionHistory={() => setCurrentPage('emotion-history')}
        onCenter={() => setCurrentPage('center')}
        onBack={moveToMain}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'center') {
    // 사이드바에서 관련 기관 찾기 선택 시 상담기관 검색 화면 렌더링.
    currentPageContent = (
      <Center
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => setCurrentPage('login')}
        onLogout={handleLogout}
        onSignUp={() => setCurrentPage('signup')}
        onEmotionHistory={() => setCurrentPage('emotion-history')}
        onCenter={() => setCurrentPage('center')}
        onHome={moveToMain}
      />
    )
  } else {
    // 기본 메인 화면과 사이드바 이동 기능 렌더링.
    currentPageContent = (
      <Main
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => setCurrentPage('login')}
        onLogout={handleLogout}
        onSignUp={() => setCurrentPage('signup')}
        onEmotionRecord={() => setCurrentPage('emotion-record')}
        onEmotionHistory={() => setCurrentPage('emotion-history')}
        onWeeklyReport={() => setCurrentPage('weekly-report')}
        onCenter={() => setCurrentPage('center')}
        onHome={moveToMain}
      />
    )
  }

  // 앱 시작 안내창과 현재 선택된 페이지의 함께 렌더링.
  return (
    <>
      {isIntroOpen && (
        <AppIntroModal
          onClose={() => setIsIntroOpen(false)}
          onHome={moveToMain}
        />
      )}
      {currentPageContent}
      {/* 네트워크 연결 해제 시 모든 화면에서 서버 기능 제한 안내 표시. */}
      <NetworkStatus />
    </>
  )
}

export default App
