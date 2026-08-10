// 로그인과 회원가입 화면 전환을 위한 리액트 상태 기능 불러오기.
import { useState } from 'react'
import './App.css'
import Login from './components/Login/Login.jsx'
import SignUp from './components/SignUp/SignUp.jsx'

// 애플리케이션의 최상위 화면을 구성하는 루트 컴포넌트 정의.
function App() {
  // 회원가입 화면 표시 여부 상태 관리.
  const [showSignUp, setShowSignUp] = useState(false)

  // 회원가입 선택 여부에 따른 화면 전환.
  return showSignUp ? <SignUp /> : <Login onSignUp={() => setShowSignUp(true)} />
}

export default App
