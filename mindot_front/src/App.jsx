// 메인, 로그인, 회원가입, 감정 기록, CBT, 주간 리포트 화면 전환을 위한 리액트 상태 기능 불러오기.
import { useState } from 'react'
import './App.css'
import Main from './components/Main/Main.jsx'
import Login from './components/Login/Login.jsx'
import SignUp from './components/SignUp/SignUp.jsx'
import EmotionRecord from './components/EmotionRecord/EmotionRecord.jsx'
import CBT from './components/CBT/CBT.jsx'
import WeeklyReport from './components/WeeklyReport/WeeklyReport.jsx'
import AppIntroModal from './components/AppIntroModal/AppIntroModal.jsx'

// 애플리케이션의 최상위 화면을 구성하는 루트 컴포넌트 정의.
function App() {
  // 현재 표시할 화면 상태 관리.
  const [currentPage, setCurrentPage] = useState('main')
  // 앱을 처음 열었을 때 서비스 안내창을 표시하기 위한 상태 관리.
  const [isIntroOpen, setIsIntroOpen] = useState(true)
  // 현재 화면 상태에 따라 렌더링할 페이지 컴포넌트 보관.
  let currentPageContent

  // 로그인 화면 선택 시 로그인 컴포넌트 렌더링.
  if (currentPage === 'login') {
    currentPageContent = (
      <Login
        onLoginSuccess={() => setCurrentPage('main')}
        onSignUp={() => setCurrentPage('signup')}
      />
    )
  } else if (currentPage === 'signup') {
    // 회원가입 화면 선택 시 회원가입 컴포넌트 렌더링.
    currentPageContent = <SignUp />
  } else if (currentPage === 'emotion-record') {
    // 감정 기록 화면 선택 시 감정 기록 컴포넌트 렌더링.
    currentPageContent = (
      <EmotionRecord
        onCBT={() => setCurrentPage('cbt')}
        onWeeklyReport={() => setCurrentPage('weekly-report')}
      />
    )
  } else if (currentPage === 'cbt') {
    // 감정 기록 저장 완료 후 선택한 CBT 성찰 화면 렌더링.
    currentPageContent = <CBT />
  } else if (currentPage === 'weekly-report') {
    // 주간 리포트 화면 선택 시 간단한 리포트 초안 컴포넌트 렌더링.
    currentPageContent = <WeeklyReport onBack={() => setCurrentPage('main')} />
  } else {
    // 기본 메인 화면과 사이드바 이동 기능 렌더링.
    currentPageContent = (
      <Main
        onLogin={() => setCurrentPage('login')}
        onSignUp={() => setCurrentPage('signup')}
        onEmotionRecord={() => setCurrentPage('emotion-record')}
        onWeeklyReport={() => setCurrentPage('weekly-report')}
      />
    )
  }

  // 앱 시작 안내창과 현재 선택된 페이지의 함께 렌더링.
  return (
    <>
      {isIntroOpen && (
        <AppIntroModal onClose={() => setIsIntroOpen(false)} />
      )}
      {currentPageContent}
    </>
  )
}

export default App
