// 메인, 로그인, 회원가입, 감정 기록 화면 전환을 위한 리액트 상태 기능 불러오기.
import { useState } from 'react'
import './App.css'
import Main from './components/Main/Main.jsx'
import Login from './components/Login/Login.jsx'
import SignUp from './components/SignUp/SignUp.jsx'
import EmotionRecord from './components/EmotionRecord/EmotionRecord.jsx'

// 애플리케이션의 최상위 화면을 구성하는 루트 컴포넌트 정의.
function App() {
  // 현재 표시할 화면 상태 관리.
  const [currentPage, setCurrentPage] = useState('main')

  // 로그인 화면 선택 시 로그인 컴포넌트 렌더링.
  if (currentPage === 'login') {
    return <Login onSignUp={() => setCurrentPage('signup')} />
  }

  // 회원가입 화면 선택 시 회원가입 컴포넌트 렌더링.
  if (currentPage === 'signup') {
    return <SignUp />
  }

  // 감정 기록 화면 선택 시 감정 기록 컴포넌트 렌더링.
  if (currentPage === 'emotion-record') {
    return <EmotionRecord />
  }

  // 기본 메인 화면과 사이드바 이동 기능 렌더링.
  return (
    <Main
      onLogin={() => setCurrentPage('login')}
      onSignUp={() => setCurrentPage('signup')}
      onEmotionRecord={() => setCurrentPage('emotion-record')}
    />
  )
}

export default App
