import { useEffect, useState } from 'react'
import './App.css'
import Main from './components/Main/Main.jsx'
import Login from './components/Login/Login.jsx'
import SignUp from './components/SignUp/SignUp.jsx'
import EmotionRecord from './components/EmotionRecord/EmotionRecord.jsx'
import EmotionHistory from './components/EmotionHistory/EmotionHistory.jsx'
import EmotionRecordDetail from './components/EmotionRecordDetail/EmotionRecordDetail.jsx'
import CBT from './components/CBT/CBT.jsx'
import WeeklyReport from './components/WeeklyReport/WeeklyReport.jsx'
import CompletedReflection from './components/CompletedReflection/CompletedReflection.jsx'
import AppIntroModal from './components/AppIntroModal/AppIntroModal.jsx'
import Center from './components/Center/Center.jsx'
import DailyCare from './components/DailyCare/DailyCare.jsx'
import LoginRequiredModal from './components/LoginRequiredModal/LoginRequiredModal.jsx'
import NetworkStatus from './components/NetworkStatus/NetworkStatus.jsx'
import PwaInstallPrompt from './components/PwaInstallPrompt/PwaInstallPrompt.jsx'
import { logout } from './utils/auth/authApi.js'
import { getAccessToken } from './utils/auth/tokenStorage.js'
import { createAppPath, readAppRoute } from './utils/routing/appRouter.js'

// 브라우저 주소에서 최초 화면과 상세 식별자를 읽어 오는 초기 라우트 설정.
const browserInitialRoute = readAppRoute()
// 직접 URL로 접근해도 기존 로그인 제한을 유지할 보호 화면 목록 설정.
const protectedPages = new Set([
  'emotion-history',
  'emotion-record-detail',
  'cbt',
  'weekly-report',
  'completed-reflection',
  'daily-care',
])
// 최초 URL의 보호 화면 접근 가능 여부 확인.
const isInitialRouteBlocked = (
  protectedPages.has(browserInitialRoute.page)
  && !getAccessToken()
)
// 비로그인 보호 URL 접근 시 안전하게 메인 화면을 최초 화면으로 설정.
const initialRoute = isInitialRouteBlocked
  ? { page: 'main' }
  : browserInitialRoute

// 애플리케이션의 최상위 화면을 구성하는 루트 컴포넌트 정의.
function App() {
  // 현재 표시할 화면 상태 관리.
  const [currentPage, setCurrentPage] = useState(initialRoute.page)
  // 앱을 처음 열었을 때 서비스 안내창을 표시하기 위한 상태 관리.
  const [isIntroOpen, setIsIntroOpen] = useState(true)
  // 브라우저에 저장된 Access Token을 기준으로 로그인 여부 상태 관리.
  const [isAuthenticated, setIsAuthenticated] = useState(
    () => Boolean(getAccessToken()),
  )
  // 중복 로그아웃 요청을 방지하기 위한 진행 상태 관리.
  const [isLoggingOut, setIsLoggingOut] = useState(false)
  // 비로그인 사용자의 보호 기능 선택 시 안내 모달 표시 상태 관리.
  const [isLoginRequiredOpen, setIsLoginRequiredOpen] = useState(
    isInitialRouteBlocked,
  )
  // CBT 성찰을 시작할 저장 완료 감정 기록 식별자 상태 관리.
  const [cbtEmotionRecordId, setCbtEmotionRecordId] = useState(
    initialRoute.page === 'cbt' ? initialRoute.emotionRecordId ?? null : null,
  )
  // URL로 직접 진입한 CBT 재개 화면의 성찰 세션 식별자 상태 관리.
  const [cbtResumeSessionId, setCbtResumeSessionId] = useState(
    initialRoute.page === 'cbt' ? initialRoute.reflectionSessionId ?? null : null,
  )
  // 감정 기록 목록에서 이어서 진행할 OPEN CBT 세션 상세 상태 관리.
  const [cbtResumeSession, setCbtResumeSession] = useState(null)
  // 감정 기록 목록에서 선택한 상세 조회 대상 식별자 상태 관리.
  const [selectedEmotionRecordId, setSelectedEmotionRecordId] = useState(
    initialRoute.page === 'emotion-record-detail'
      ? initialRoute.emotionRecordId ?? null
      : null,
  )
  // 주간 리포트에서 선택한 완료 CBT 성찰 세션 식별자 상태 관리.
  const [selectedReflectionSessionId, setSelectedReflectionSessionId] = useState(
    initialRoute.page === 'completed-reflection'
      ? initialRoute.reflectionSessionId ?? null
      : null,
  )

  // 화면 상태와 상세 식별자를 브라우저 주소에 함께 반영하는 이동 처리.
  const moveToPage = (page, parameters = {}, options = {}) => {
    const nextPath = createAppPath(page, parameters)
    const currentPath = `${window.location.pathname}${window.location.search}`

    if (currentPath !== nextPath) {
      const historyMethod = options.replace ? 'replaceState' : 'pushState'
      window.history[historyMethod]({ page }, '', nextPath)
    }

    setCurrentPage(page)
    setCbtEmotionRecordId(
      page === 'cbt' ? parameters.emotionRecordId ?? null : null,
    )
    setCbtResumeSessionId(
      page === 'cbt' ? parameters.reflectionSessionId ?? null : null,
    )
    setSelectedEmotionRecordId(
      page === 'emotion-record-detail' ? parameters.emotionRecordId ?? null : null,
    )
    setSelectedReflectionSessionId(
      page === 'completed-reflection'
        ? parameters.reflectionSessionId ?? null
        : null,
    )
  }

  // 브라우저 뒤로 가기와 앞으로 가기 시 URL에 해당하는 화면 상태 복원.
  useEffect(() => {
    // 비로그인 상태의 최초 보호 URL을 메인 주소로 교체하는 처리.
    if (isInitialRouteBlocked) {
      window.history.replaceState({ page: 'main' }, '', '/')
    }

    const handlePopState = () => {
      let route = readAppRoute()

      if (!isAuthenticated && protectedPages.has(route.page)) {
        setIsLoginRequiredOpen(true)
        window.history.replaceState({ page: 'main' }, '', '/')
        route = { page: 'main' }
      }

      setCbtResumeSession(null)
      setCurrentPage(route.page)
      setCbtEmotionRecordId(
        route.page === 'cbt' ? route.emotionRecordId ?? null : null,
      )
      setCbtResumeSessionId(
        route.page === 'cbt' ? route.reflectionSessionId ?? null : null,
      )
      setSelectedEmotionRecordId(
        route.page === 'emotion-record-detail'
          ? route.emotionRecordId ?? null
          : null,
      )
      setSelectedReflectionSessionId(
        route.page === 'completed-reflection'
          ? route.reflectionSessionId ?? null
          : null,
      )
    }

    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [isAuthenticated])

  // 각 화면의 로고 선택 시 URL과 함께 메인페이지로 이동하는 처리.
  const moveToMain = () => moveToPage('main')
  // 로그인 성공 후 인증 상태 반영과 메인페이지 이동 처리.
  const handleLoginSuccess = () => {
    setIsAuthenticated(true)
    setIsLoginRequiredOpen(false)
    moveToMain()
  }
  // 로그인 여부 확인 후 보호 화면 이동 또는 로그인 필요 안내 표시 처리.
  const moveToProtectedPage = (pageName) => {
    if (!isAuthenticated) {
      setIsLoginRequiredOpen(true)
      return
    }

    moveToPage(pageName)
  }
  // 로그인 필요 안내를 닫고 로그인 화면으로 이동하는 처리.
  const moveToLoginFromRequiredModal = () => {
    setIsLoginRequiredOpen(false)
    moveToPage('login')
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
    setCbtResumeSession(null)
    moveToPage('cbt', { emotionRecordId })
  }
  // 새 CBT 세션 생성 후 현재 기록 URL을 재개 가능한 세션 URL로 교체하는 처리.
  const handleCbtSessionStarted = (reflectionSessionId) => {
    const nextPath = createAppPath('cbt', { reflectionSessionId })

    window.history.replaceState({ page: 'cbt' }, '', nextPath)
  }
  // 선택한 OPEN 성찰의 기존 대화 이력을 보관하고 CBT 화면으로 이동하는 처리.
  const handleReflectionResume = (reflectionSession) => {
    setCbtResumeSession(reflectionSession)
    moveToPage('cbt', {
      emotionRecordId: reflectionSession.emotionRecordId ?? null,
      reflectionSessionId: reflectionSession.sessionId,
    })
  }
  // 목록에서 선택한 감정 기록 식별자를 보관하고 상세 화면으로 이동하는 처리.
  const handleEmotionRecordDetailOpen = (emotionRecordId) => {
    moveToPage('emotion-record-detail', { emotionRecordId })
  }
  // 완료된 CBT 성찰 식별자를 보관하고 결과 상세 화면으로 이동하는 처리.
  const handleCompletedReflectionOpen = (sessionId) => {
    moveToPage('completed-reflection', { reflectionSessionId: sessionId })
  }
  // 현재 화면 상태에 따라 렌더링할 페이지 컴포넌트 보관.
  let currentPageContent

  // 로그인 화면 선택 시 로그인 컴포넌트 렌더링.
  if (currentPage === 'login') {
    currentPageContent = (
      <Login
        onLoginSuccess={handleLoginSuccess}
        onSignUp={() => moveToPage('signup')}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'signup') {
    // 회원가입 화면 선택 시 회원가입 컴포넌트 렌더링.
    currentPageContent = (
      <SignUp
        onSignUpSuccess={() => moveToPage('login')}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'emotion-record') {
    // 감정 기록 화면 선택 시 감정 기록 컴포넌트 렌더링.
    currentPageContent = (
      <EmotionRecord
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onCBT={handleCbtOpen}
        onWeeklyReport={() => moveToProtectedPage('weekly-report')}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'emotion-history') {
    // 감정 기록 목록 화면 선택 시 빈 목록 초안과 이동 기능 렌더링.
    currentPageContent = (
      <EmotionHistory
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onRecordDetail={handleEmotionRecordDetailOpen}
        onEmotionRecord={() => moveToPage('emotion-record')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onReflectionResume={handleReflectionResume}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'emotion-record-detail') {
    // 감정 기록 목록에서 선택한 한 건의 상세 조회 화면 렌더링.
    currentPageContent = (
      <EmotionRecordDetail
        emotionRecordId={selectedEmotionRecordId}
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onBack={() => moveToPage('emotion-history')}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'cbt') {
    // 감정 기록 저장 완료 후 선택한 CBT 성찰 화면 렌더링.
    currentPageContent = (
      <CBT
        key={cbtResumeSessionId ?? cbtEmotionRecordId ?? 'cbt'}
        emotionRecordId={cbtEmotionRecordId}
        resumeSession={cbtResumeSession}
        resumeSessionId={cbtResumeSessionId}
        onSessionStarted={handleCbtSessionStarted}
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'weekly-report') {
    // 주간 리포트 화면 선택 시 간단한 리포트 초안 컴포넌트 렌더링.
    currentPageContent = (
      <WeeklyReport
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onRecordDetail={handleEmotionRecordDetailOpen}
        onCompletedReflection={handleCompletedReflectionOpen}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onBack={moveToMain}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'completed-reflection') {
    // 주간 리포트에서 선택한 완료 CBT 성찰 결과 상세 화면 렌더링.
    currentPageContent = (
      <CompletedReflection
        sessionId={selectedReflectionSessionId}
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onBack={() => moveToPage('weekly-report')}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'center') {
    // 사이드바에서 관련 기관 찾기 선택 시 상담기관 검색 화면 렌더링.
    currentPageContent = (
      <Center
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'daily-care') {
    // 사이드바에서 마음 돌봄 추천 선택 시 기본 추천 화면 렌더링.
    currentPageContent = (
      <DailyCare
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onHome={moveToMain}
        onCBT={() => {
          setCbtResumeSession(null)
          moveToPage('cbt')
        }}
      />
    )
  } else {
    // 기본 메인 화면과 사이드바 이동 기능 렌더링.
    currentPageContent = (
      <Main
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionRecord={() => moveToPage('emotion-record')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onWeeklyReport={() => moveToProtectedPage('weekly-report')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
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
      {/* 비로그인 사용자의 보호 기능 선택 시 로그인 필요 안내 표시. */}
      {isLoginRequiredOpen && !isIntroOpen && (
        <LoginRequiredModal
          onClose={() => setIsLoginRequiredOpen(false)}
          onLogin={moveToLoginFromRequiredModal}
        />
      )}
      {/* 시작 안내창을 닫은 뒤 PWA 설치 버튼 또는 수동 설치 방법 안내 표시. */}
      {!isIntroOpen && <PwaInstallPrompt />}
      {/* 네트워크 연결 해제 시 모든 화면에서 서버 기능 제한 안내 표시. */}
      <NetworkStatus />
    </>
  )
}

export default App
