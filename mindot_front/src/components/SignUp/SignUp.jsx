import { useState } from 'react'
import './SignUp.css'

// 이메일과 비밀번호를 입력받는 기본 회원가입 컴포넌트 정의.
function SignUp() {
  // 이메일 입력값 상태 관리.
  const [email, setEmail] = useState('')
  // 비밀번호 입력값 상태 관리.
  const [password, setPassword] = useState('')
  // 비밀번호 확인 입력값 상태 관리.
  const [passwordConfirm, setPasswordConfirm] = useState('')

  // 실제 회원가입 API 연결 전 폼 새로고침 방지.
  const handleSubmit = (event) => {
    event.preventDefault()
  }

  // 기본 회원가입 화면 반환.
  return (
    <main className="signup-page">
      <section className="signup-card" aria-labelledby="signup-title">
        <h1 id="signup-title">회원가입</h1>

        {/* 이메일과 비밀번호를 입력받는 기본 회원가입 폼 배치. */}
        <form className="signup-form" onSubmit={handleSubmit}>
          <label htmlFor="signup-email">이메일</label>
          <input
            id="signup-email"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />

          <label htmlFor="signup-password">비밀번호</label>
          <input
            id="signup-password"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />

          <label htmlFor="signup-password-confirm">비밀번호 확인</label>
          <input
            id="signup-password-confirm"
            type="password"
            value={passwordConfirm}
            onChange={(event) => setPasswordConfirm(event.target.value)}
            required
          />

          <button type="submit">가입하기</button>
        </form>
      </section>
    </main>
  )
}

export default SignUp
