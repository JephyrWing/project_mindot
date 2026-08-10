import { useState } from 'react'
// 여러 화면에서 공통으로 사용하는 Mindot 로고 불러오기.
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import { login } from '../../utils/auth/authApi.js'
import './Login.css'

// 브라우저에 저장할 이메일 데이터의 식별 키 설정.
const rememberedEmailKey = 'mindot.rememberedEmail'

// 이메일과 비밀번호를 입력받는 기본 로그인 컴포넌트 정의.
function Login({ onLoginSuccess, onSignUp }) {
  // 이메일 입력값 상태 관리.
  const [email, setEmail] = useState(() => localStorage.getItem(rememberedEmailKey) ?? '')
  // 비밀번호 입력값 상태 관리.
  const [password, setPassword] = useState('')
  // 비밀번호 표시 여부 상태 관리.
  const [showPassword, setShowPassword] = useState(false)
  // 이메일 기억 여부 상태 관리.
  const [rememberEmail, setRememberEmail] = useState(
    () => localStorage.getItem(rememberedEmailKey) !== null,
  )
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [loginError, setLoginError] = useState('')

  // 로그인 API 호출과 기존 이메일 기억하기 동작 처리.
  const handleSubmit = async (event) => {
    event.preventDefault()

    if (isSubmitting) {
      return
    }

    // 선택 상태에 따른 이메일 저장 또는 기존 저장값 삭제.
    if (rememberEmail) {
      localStorage.setItem(rememberedEmailKey, email)
    } else {
      localStorage.removeItem(rememberedEmailKey)
    }

    setIsSubmitting(true)
    setLoginError('')

    try {
      await login({ email, password })
      onLoginSuccess()
    } catch {
      setLoginError('로그인에 실패했습니다. 이메일과 비밀번호를 확인해주세요.')
    } finally {
      setIsSubmitting(false)
    }
  }

  // 회원가입과 동일한 배경 및 브랜드 안내가 포함된 로그인 화면 반환.
  return (
    <main className="login-page app-page">
      <section className="login-card" aria-labelledby="login-title">
        {/* 공통 컴포넌트를 사용한 Mindot 로고 배치. */}
        <BrandLogo className="login-logo" />

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
          {/* 비밀번호 입력창과 표시 전환 버튼 묶음 배치. */}
          <div className="login-password-field">
            <input
              id="login-password"
              type={showPassword ? 'text' : 'password'}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
            />
            <button
              className="login-password-toggle"
              type="button"
              onClick={() => setShowPassword((current) => !current)}
              aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 보기'}
            >
              {showPassword ? '숨기기' : '보기'}
            </button>
          </div>

          {/* 이메일 저장 여부를 선택하는 체크박스 배치. */}
          <label className="login-remember" htmlFor="remember-email">
            <input
              id="remember-email"
              type="checkbox"
              checked={rememberEmail}
              onChange={(event) => setRememberEmail(event.target.checked)}
            />
            <span>이메일 기억하기</span>
          </label>

          {loginError && (
            <p className="login-error" role="alert">{loginError}</p>
          )}

          <button type="submit" disabled={isSubmitting}>
            {isSubmitting ? '로그인 중...' : '로그인'}
          </button>
        </form>

        {/* 회원가입 화면으로 이동하기 위한 안내와 버튼 배치. */}
        <p className="login-signup-guide">
          아직 계정이 없으신가요?
          <button type="button" onClick={onSignUp}>회원가입</button>
        </p>

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
