import { useState } from 'react'
import './Login.css'

// 이메일과 비밀번호를 입력받는 기본 로그인 컴포넌트
function Login() {
  // 이메일 입력값을 관리한다.
  const [email, setEmail] = useState('')
  // 비밀번호 입력값을 관리한다.
  const [password, setPassword] = useState('')

  // 실제 로그인 API를 연결하기 전까지 폼의 새로고침만 방지
  const handleSubmit = (event) => {
    event.preventDefault()
  }

  // 기본 로그인 화면을 반환
  return (
    <main className="login-page app-page">
      <section className="login-card">
        <h1>로그인</h1>

        <form className="login-form" onSubmit={handleSubmit}>
          <label htmlFor="login-email">이메일</label>
          <input
            id="login-email"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />

          <label htmlFor="login-password">비밀번호</label>
          <input
            id="login-password"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />

          <button type="submit">로그인</button>
        </form>
      </section>
    </main>
  )
}

export default Login
