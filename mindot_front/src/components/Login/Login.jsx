import { useState } from 'react'
import './Login.css'

// 이메일과 비밀번호를 입력받는 기본 로그인 컴포넌트 정의.
function Login() {
  // 이메일 입력값 상태 관리.
  const [email, setEmail] = useState('')
  // 비밀번호 입력값 상태 관리.
  const [password, setPassword] = useState('')

  // 실제 로그인 API 연결 전 폼 새로고침 방지.
  const handleSubmit = (event) => {
    event.preventDefault()
  }

  // 배경 장식과 브랜드 안내가 포함된 로그인 화면 반환.
  return (
    <main className="login-page app-page">
      {/* 로그인 카드 뒤쪽의 원형 배경 장식 배치. */}
      <span className="login-decoration login-decoration--one" aria-hidden="true" />
      <span className="login-decoration login-decoration--two" aria-hidden="true" />

      <section className="login-card" aria-labelledby="login-title">
        {/* 점과 연결선으로 구성한 Mindot 로고 배치. */}
        <div className="login-logo" aria-label="Mindot">
          <span className="login-logo__mark" aria-hidden="true">
            <span />
            <span />
            <span />
          </span>
          <span>MINDOT</span>
        </div>

        {/* 로그인 제목과 서비스 안내 문구 배치. */}
        <header className="login-header">
          <h1 id="login-title">로그인</h1>
          <p>오늘의 마음을 기록하고, 나만의 패턴을 이어가세요.</p>
        </header>

        {/* 이메일과 비밀번호를 입력받는 기본 로그인 폼 배치. */}
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

        {/* 서비스의 역할과 의료적 이용 범위를 알리는 안내 문구 배치. */}
        <p className="login-notice">
          Mindot은 자기기록과 자기이해를 돕는 도구이며,
          의료적 진단이나 치료를 대신하지 않습니다.
        </p>
      </section>
    </main>
  )
}

export default Login
