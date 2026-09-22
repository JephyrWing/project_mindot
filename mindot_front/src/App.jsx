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
import MonthlyReport from './components/MonthlyReport/MonthlyReport.jsx'
import EmotionInsights from './components/EmotionInsights/EmotionInsights.jsx'
import PatternInsights from './components/PatternInsights/PatternInsights.jsx'
import CompletedReflection from './components/CompletedReflection/CompletedReflection.jsx'
import AppIntroModal from './components/AppIntroModal/AppIntroModal.jsx'
import Center from './components/Center/Center.jsx'
import DailyCare from './components/DailyCare/DailyCare.jsx'
import Breathing from './components/Breathing/Breathing.jsx'
import Meditation from './components/Meditation/Meditation.jsx'
import Admin from './components/Admin/Admin.jsx'
import LoginRequiredModal from './components/LoginRequiredModal/LoginRequiredModal.jsx'
import NetworkStatus from './components/NetworkStatus/NetworkStatus.jsx'
import PwaInstallPrompt from './components/PwaInstallPrompt/PwaInstallPrompt.jsx'
import AccessDeniedModal from './components/AccessDeniedModal/AccessDeniedModal.jsx'
import OAuthCallback from './components/OAuthCallback/OAuthCallback.jsx'
import Settings from './components/Settings/Settings.jsx'
import ScrollToTop from './components/ScrollToTop/ScrollToTop.jsx'
import ServiceInfo from './components/ServiceInfo/ServiceInfo.jsx'
import {
  logout,
  restoreAuthentication,
} from './utils/auth/authApi.js'
import {
  clearAuthSession,
  getAccessToken,
} from './utils/auth/tokenStorage.js'
import {
  accessDeniedEventName,
  authExpiredEventName,
} from './utils/auth/authEvents.js'
import { createAppPath, readAppRoute } from './utils/routing/appRouter.js'

// 브라우저 주소에서 최초 화면과 상세 식별자를 읽어 오는 초기 라우트 설정.
const browserInitialRoute = readAppRoute()
// 직접 URL로 접근해도 기존 로그인 제한을 유지할 보호 화면 목록 설정.
const protectedPages = new Set([
  'emotion-record',
  'emotion-history',
  'emotion-record-detail',
  'cbt',
  'weekly-report',
  'monthly-report',
  'emotion-insights',
  'pattern-insights',
  'pattern-detail',
  'completed-reflection',
  'daily-care',
  'breathing',
  'meditation',
  'admin',
  'settings',
])
// 인증 확인 전에도 사용자가 요청한 최초 URL은 그대로 유지.
const initialRoute = browserInitialRoute

// 브라우저에서 시작 안내창을 이미 표시했는지 보관하는 저장소 키 설정.
const introShownStorageKey = 'mindot.appIntroShown'

// 메인 주소로 처음 진입했고 안내 이력이 없을 때만 시작 안내창 표시 여부 반환.
const shouldShowIntroInitially = () => {
  if (initialRoute.page !== 'main') return false

  try {
    return window.localStorage.getItem(introShownStorageKey) !== 'true'
  } catch {
    // 브라우저 저장소를 사용할 수 없는 환경에서는 현재 진입에 한해 안내 표시.
    return true
  }
}

// 애플리케이션의 최상위 화면을 구성하는 루트 컴포넌트 정의.
function App() {
  // 현재 표시할 화면 상태 관리.
  const [currentPage, setCurrentPage] = useState(initialRoute.page)
  // 브라우저 기준 최초 메인 진입에서만 서비스 안내창을 표시하기 위한 상태 관리.
  const [isIntroOpen, setIsIntroOpen] = useState(shouldShowIntroInitially)
  // 현재 JavaScript 실행 메모리의 Access Token을 기준으로 로그인 여부 상태 관리.
  const [isAuthenticated, setIsAuthenticated] = useState(
    () => Boolean(getAccessToken()),
  )
  // 새로고침 후 HttpOnly Refresh Token 쿠키로 메모리 토큰을 복구하는 상태 관리.
  const [isAuthChecking, setIsAuthChecking] = useState(
    () => !getAccessToken(),
  )
  // 중복 로그아웃 요청을 방지하기 위한 진행 상태 관리.
  const [isLoggingOut, setIsLoggingOut] = useState(false)
  // 비로그인 사용자의 보호 기능 선택 시 안내 모달 표시 상태 관리.
  const [isLoginRequiredOpen, setIsLoginRequiredOpen] = useState(
    false,
  )
  // 로그인 계정의 서비스 접근 권한 부족 안내 모달 표시 상태 관리.
  const [isAccessDeniedOpen, setIsAccessDeniedOpen] = useState(false)
  // 소셜 로그인 콜백을 전달한 카카오 또는 Google 제공자 상태 관리.
  const [oauthProvider, setOauthProvider] = useState(
    initialRoute.page === 'oauth-callback' ? initialRoute.provider : null,
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
  // 감정 기록 상세 화면을 연 이전 화면 상태 관리.
  const [newlySavedRecord, setNewlySavedRecord] = useState(null)
  const [emotionRecordDetailReturnPage, setEmotionRecordDetailReturnPage] = useState(initialRoute.returnWeek ? 'weekly-report' : 'emotion-history')
  const [weeklyWeekStart, setWeeklyWeekStart] = useState(initialRoute.weekStart ?? initialRoute.returnWeek ?? null)
  // 주간 리포트에서 선택한 완료 CBT 성찰 세션 식별자 상태 관리.
  const [selectedReflectionSessionId, setSelectedReflectionSessionId] = useState(
    initialRoute.page === 'completed-reflection'
      ? initialRoute.reflectionSessionId ?? null
      : null,
  )
  // 반복 패턴 목록 또는 직접 URL에서 선택한 상세 식별자 상태 관리.
  const [selectedPatternId, setSelectedPatternId] = useState(
    initialRoute.page === 'pattern-detail'
      ? initialRoute.patternId ?? null
      : null,
  )

  // 페이지 새로고침으로 비워진 메모리 토큰을 Refresh Token 쿠키로 한 번 복구.
  useEffect(() => {
    if (getAccessToken()) return undefined

    let isActive = true

    const restoreSession = async () => {
      try {
        await restoreAuthentication()
        if (!isActive) return

        setIsAuthenticated(true)
      } catch {
        if (!isActive) return

        clearAuthSession()
        setIsAuthenticated(false)

        if (protectedPages.has(browserInitialRoute.page)) {
          setIsLoginRequiredOpen(true)
          setCbtEmotionRecordId(null)
          setCbtResumeSessionId(null)
          setSelectedEmotionRecordId(null)
          setSelectedReflectionSessionId(null)
          setSelectedPatternId(null)
          window.history.replaceState({ page: 'main' }, '', '/')
          setCurrentPage('main')
        }
      } finally {
        if (isActive) setIsAuthChecking(false)
      }
    }

    restoreSession()
    return () => {
      isActive = false
    }
  }, [])

  // 최초 시작 안내창이 표시되면 이후 재접속에서 반복되지 않도록 표시 이력 저장.
  useEffect(() => {
    if (!isIntroOpen) return

    try {
      window.localStorage.setItem(introShownStorageKey, 'true')
    } catch {
      // 저장소 사용이 제한된 환경에서도 현재 안내창 이용은 계속 허용.
    }
  }, [isIntroOpen])

  // 화면 상태와 상세 식별자를 브라우저 주소에 함께 반영하는 이동 처리.
  const moveToPage = (page, parameters = {}, options = {}) => {
    if (page === 'weekly-report') {
      parameters = { weekStart: weeklyWeekStart, ...parameters }
      setWeeklyWeekStart(parameters.weekStart ?? null)
    }
    if (parameters.returnWeek) setWeeklyWeekStart(parameters.returnWeek)
    const nextPath = createAppPath(page, parameters)
    const currentPath = `${window.location.pathname}${window.location.search}`

    if (currentPath !== nextPath) {
      const historyMethod = options.replace ? 'replaceState' : 'pushState'
      window.history[historyMethod]({ page }, '', nextPath)
    }

    setCurrentPage(page)
    setOauthProvider(
      page === 'oauth-callback' ? parameters.provider ?? null : null,
    )
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
    setSelectedPatternId(
      page === 'pattern-detail' ? parameters.patternId ?? null : null,
    )
  }

  // 브라우저 뒤로 가기와 앞으로 가기 시 URL에 해당하는 화면 상태 복원.
  useEffect(() => {
    if (isAuthChecking) return undefined

    const handlePopState = () => {
      let route = readAppRoute()

      if (!isAuthenticated && protectedPages.has(route.page)) {
        setIsLoginRequiredOpen(true)
        window.history.replaceState({ page: 'main' }, '', '/')
        route = { page: 'main' }
      }

      setCbtResumeSession(null)
      if (route.page === 'weekly-report') setWeeklyWeekStart(route.weekStart ?? null)
      else if (route.returnWeek) setWeeklyWeekStart(route.returnWeek)
      setEmotionRecordDetailReturnPage(route.returnWeek ? 'weekly-report' : 'emotion-history')
      setCurrentPage(route.page)
      setOauthProvider(
        route.page === 'oauth-callback' ? route.provider ?? null : null,
      )
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
      setSelectedPatternId(
        route.page === 'pattern-detail' ? route.patternId ?? null : null,
      )
    }

    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [isAuthChecking, isAuthenticated])

  // Access Token 재발급 실패 시 로그인 상태와 보호 화면을 즉시 정리하는 처리.
  useEffect(() => {
    const handleAuthExpired = () => {
      setIsAuthenticated(false)
      setIsLoginRequiredOpen(true)
      setCbtEmotionRecordId(null)
      setCbtResumeSessionId(null)
      setCbtResumeSession(null)
      setSelectedEmotionRecordId(null)
      setSelectedReflectionSessionId(null)
      setSelectedPatternId(null)
      setOauthProvider(null)
      window.history.replaceState({ page: 'main' }, '', '/')
      setCurrentPage('main')
    }

    window.addEventListener(authExpiredEventName, handleAuthExpired)
    return () => {
      window.removeEventListener(authExpiredEventName, handleAuthExpired)
    }
  }, [])

  // 일반 서비스 API의 403 응답 수신 시 공통 권한 안내 모달 표시 처리.
  useEffect(() => {
    const handleAccessDenied = () => setIsAccessDeniedOpen(true)

    window.addEventListener(accessDeniedEventName, handleAccessDenied)
    return () => {
      window.removeEventListener(accessDeniedEventName, handleAccessDenied)
    }
  }, [])

  // 각 화면의 로고 선택 시 URL과 함께 메인페이지로 이동하는 처리.
  const moveToMain = () => moveToPage('main')
  // 로그인 성공 후 인증 상태 반영과 메인페이지 이동 처리.
  const handleLoginSuccess = () => {
    setIsAuthenticated(true)
    setIsLoginRequiredOpen(false)
    moveToMain()
  }
  // 소셜 로그인 또는 신규 소셜 회원가입 성공 후 콜백 주소를 메인 주소로 교체하는 처리.
  const handleSocialLoginSuccess = () => {
    setIsAuthenticated(true)
    setIsLoginRequiredOpen(false)
    moveToPage('main', {}, { replace: true })
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
  // 회원 탈퇴 성공 후 로컬 인증 상태와 보호 화면 정보 정리.
  const handleWithdrawalSuccess = () => {
    clearAuthSession()
    setIsAuthenticated(false)
    setCbtEmotionRecordId(null)
    setCbtResumeSessionId(null)
    setCbtResumeSession(null)
    setSelectedEmotionRecordId(null)
    setSelectedReflectionSessionId(null)
    setOauthProvider(null)
    moveToPage('main', {}, { replace: true })
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
  const handleEmotionRecordDetailOpen = (emotionRecordId, returnPage = 'emotion-history', savedRecord = null, returnWeek = null) => {
    setNewlySavedRecord(savedRecord)
    setEmotionRecordDetailReturnPage(returnPage)
    moveToPage('emotion-record-detail', { emotionRecordId, returnWeek })
  }
  // 완료된 CBT 성찰 식별자를 보관하고 결과 상세 화면으로 이동하는 처리.
  const handleCompletedReflectionOpen = (sessionId, returnWeek) => {
    moveToPage('completed-reflection', { reflectionSessionId: sessionId, returnWeek })
  }

  // 인증 복구가 끝나기 전에는 보호 화면이나 비로그인 화면을 먼저 노출하지 않음.
  if (isAuthChecking) {
    return (
      <main className="auth-session-loading">
        <p role="status">로그인 상태를 확인하고 있습니다.</p>
      </main>
    )
  }

  // 현재 화면 상태에 따라 렌더링할 페이지 컴포넌트 보관.
  let currentPageContent

  // 로그인 화면 선택 시 로그인 컴포넌트 렌더링.
  if (currentPage === 'oauth-callback') {
    // 카카오 또는 Google 제공자 콜백 검증과 신규 회원 동의 화면 렌더링.
    currentPageContent = (
      <OAuthCallback
        key={oauthProvider}
        provider={oauthProvider}
        onLoginSuccess={handleSocialLoginSuccess}
        onLogin={() => moveToPage('login', {}, { replace: true })}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'login') {
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
        onRecordDetail={handleEmotionRecordDetailOpen}
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
        onEmotionRecord={() => moveToProtectedPage('emotion-record')}
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
        key={selectedEmotionRecordId}
        initialSavedRecord={Number(newlySavedRecord?.recordId) === Number(selectedEmotionRecordId) ? newlySavedRecord : null}
        emotionRecordId={selectedEmotionRecordId}
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onCBT={handleCbtOpen}
        backLabel={emotionRecordDetailReturnPage === 'weekly-report'
          ? '주간 리포트로 돌아가기'
          : '목록으로'}
        onBack={() => moveToPage(emotionRecordDetailReturnPage)}
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
        weekStart={weeklyWeekStart}
        onWeekChange={(weekStart) => moveToPage('weekly-report', { weekStart })}
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onRecordDetail={(emotionRecordId, returnWeek) => handleEmotionRecordDetailOpen(
          emotionRecordId,
          'weekly-report',
          null,
          returnWeek,
        )}
        onCompletedReflection={handleCompletedReflectionOpen}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onMonthlyReport={() => moveToPage('monthly-report')}
        onBack={moveToMain}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'monthly-report') {
    // 백엔드 월간 리포트 API와 연결된 선택 월 요약 화면 렌더링.
    currentPageContent = (
      <MonthlyReport
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onWeeklyReport={() => moveToPage('weekly-report')}
        onBack={moveToMain}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'emotion-insights') {
    // 확정된 감정 기록을 시간대·상황·관계별로 비교하는 전용 인사이트 화면 렌더링.
    currentPageContent = (
      <EmotionInsights
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
  } else if (currentPage === 'pattern-insights') {
    // 확인된 반복 패턴 목록과 상세 진입 화면 렌더링.
    currentPageContent = (
      <PatternInsights
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onPatternDetail={(patternId) => moveToPage('pattern-detail', { patternId })}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'pattern-detail') {
    // 선택한 반복 패턴의 근거 기록과 서버 저장 피드백 화면 렌더링.
    currentPageContent = (
      <PatternInsights
        key={selectedPatternId}
        patternId={selectedPatternId}
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onBack={() => moveToPage('pattern-insights')}
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
        onEmotionRecord={() => moveToProtectedPage('emotion-record')}
        onCBT={handleCbtOpen}
        onReflectionResume={handleReflectionResume}
        onBreathing={() => moveToProtectedPage('breathing')}
        onMeditation={() => moveToProtectedPage('meditation')}
      />
    )
  } else if (currentPage === 'breathing') {
    // 마음 돌봄 추천에서 3분 호흡 선택 시 전용 기본 화면 렌더링.
    currentPageContent = (
      <Breathing
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onBack={() => moveToPage('daily-care')}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'meditation') {
    // 마음 돌봄 추천에서 짧은 명상 선택 시 전용 기본 화면 렌더링.
    currentPageContent = (
      <Meditation
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onBack={() => moveToPage('daily-care')}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'settings') {
    // 로그인 사용자의 프로필과 반복 패턴 알림 설정 화면 렌더링.
    currentPageContent = (
      <Settings
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onWithdrawalSuccess={handleWithdrawalSuccess}
        onHome={moveToMain}
      />
    )
  } else if (['about', 'terms', 'privacy', 'research'].includes(currentPage)) {
    // 로그인 여부와 관계없이 확인할 수 있는 서비스·정책 안내 화면 렌더링.
    currentPageContent = (
      <ServiceInfo
        pageType={currentPage}
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onCenter={() => moveToPage('center')}
        onDailyCare={() => moveToProtectedPage('daily-care')}
        onNavigate={(page) => moveToPage(page)}
        onHome={moveToMain}
      />
    )
  } else if (currentPage === 'admin') {
    // 관리자 URL 접근 시 API 연결 전 기본 관리자 화면 렌더링.
    currentPageContent = (
      <Admin
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
  } else {
    // 기본 메인 화면과 사이드바 이동 기능 렌더링.
    currentPageContent = (
      <Main
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={() => moveToPage('login')}
        onLogout={handleLogout}
        onSignUp={() => moveToPage('signup')}
        onEmotionRecord={() => moveToProtectedPage('emotion-record')}
        onEmotionHistory={() => moveToProtectedPage('emotion-history')}
        onWeeklyReport={() => moveToProtectedPage('weekly-report')}
        onMonthlyReport={() => moveToProtectedPage('monthly-report')}
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
      {/* 로그인 계정에 선택 기능의 접근 권한이 없을 때 공통 안내 표시. */}
      {isAccessDeniedOpen && !isIntroOpen && (
        <AccessDeniedModal onClose={() => setIsAccessDeniedOpen(false)} />
      )}
      {/* 시작 안내창을 닫은 뒤 PWA 설치 버튼 또는 수동 설치 방법 안내 표시. */}
      {!isIntroOpen && currentPage !== 'oauth-callback' && <PwaInstallPrompt />}
      {!isIntroOpen && currentPage !== 'oauth-callback' && <ScrollToTop />}
      {/* 네트워크 연결 해제 시 모든 화면에서 서버 기능 제한 안내 표시. */}
      <NetworkStatus />
    </>
  )
}

export default App
