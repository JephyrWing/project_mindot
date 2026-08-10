import { useState } from 'react'
import './SignUp.css'

// 이메일과 비밀번호를 입력받는 기본 회원가입 컴포넌트 정의.
function SignUp() {
  // 이메일 입력값 상태 관리.
  const [email, setEmail] = useState('')
  // 이메일 형식 오류 문구 상태 관리.
  const [emailError, setEmailError] = useState('')
  // 비밀번호 입력값 상태 관리.
  const [password, setPassword] = useState('')
  // 비밀번호 확인 입력값 상태 관리.
  const [passwordConfirm, setPasswordConfirm] = useState('')
  // 비밀번호 일치 여부 오류 문구 상태 관리.
  const [passwordConfirmError, setPasswordConfirmError] = useState('')
  // 비밀번호 일치 확인 성공 문구 상태 관리.
  const [passwordConfirmSuccess, setPasswordConfirmSuccess] = useState('')

  // 이메일 재입력에 따른 값 반영과 이전 오류 초기화.
  const handleEmailChange = (event) => {
    event.target.setCustomValidity('')
    setEmail(event.target.value)
    setEmailError('')
  }

  // 빈 이메일과 잘못된 이메일 형식에 대한 안내 문구 설정.
  const handleEmailInvalid = (event) => {
    const errorMessage = event.target.validity.valueMissing
      ? '이메일을 입력해 주세요.'
      : '올바른 이메일 형식으로 입력해 주세요.'

    event.target.setCustomValidity(errorMessage)
    setEmailError(errorMessage)
  }

  // 비밀번호와 비밀번호 확인 값의 일치 여부 검사.
  const validatePasswordConfirm = () => {
    let errorMessage = ''

    if (!passwordConfirm) {
      errorMessage = '비밀번호 확인을 입력해 주세요.'
    } else if (password !== passwordConfirm) {
      errorMessage = '비밀번호가 서로 다릅니다.'
    }

    setPasswordConfirmError(errorMessage)
    setPasswordConfirmSuccess(
      errorMessage === '' ? '비밀번호가 일치합니다.' : '',
    )
    return errorMessage === ''
  }

  // 비밀번호 확인 재입력에 따른 값 반영과 이전 오류 초기화.
  const handlePasswordConfirmChange = (event) => {
    setPasswordConfirm(event.target.value)
    setPasswordConfirmError('')
    setPasswordConfirmSuccess('')
  }

  // 비밀번호 재입력에 따른 값 반영과 이전 불일치 오류 초기화.
  const handlePasswordChange = (event) => {
    setPassword(event.target.value)
    setPasswordConfirmError('')
    setPasswordConfirmSuccess('')
  }

  // 실제 회원가입 API 연결 전 폼 새로고침 방지.
  const handleSubmit = (event) => {
    event.preventDefault()

    // 비밀번호가 서로 다른 경우 회원가입 처리 중단.
    if (!validatePasswordConfirm()) return
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
            onChange={handleEmailChange}
            onInvalid={handleEmailInvalid}
            pattern="^[^\s@]+@[^\s@]+\.[^\s@]+$"
            placeholder="name@example.com"
            title="name@example.com 형식으로 입력해 주세요."
            aria-invalid={Boolean(emailError)}
            aria-describedby={
              emailError
                ? 'signup-email-hint signup-email-error'
                : 'signup-email-hint'
            }
            required
          />
          {/* 사용자가 지켜야 할 이메일 형식을 보여 주는 안내 문구 표시. */}
          <p className="signup-form__hint" id="signup-email-hint">
            이메일 형식: name@example.com
          </p>
          {/* 이메일 입력 오류 발생 시 사용자 안내 문구 표시. */}
          {emailError && (
            <p className="signup-form__error" id="signup-email-error">
              {emailError}
            </p>
          )}

          <label htmlFor="signup-password">비밀번호</label>
          <input
            id="signup-password"
            type="password"
            value={password}
            onChange={handlePasswordChange}
            required
          />

          <label htmlFor="signup-password-confirm">비밀번호 확인</label>
          {/* 비밀번호 확인 입력창과 일치 확인 버튼의 가로 배치. */}
          <div className="signup-password-confirm-row">
            <input
              id="signup-password-confirm"
              type="password"
              value={passwordConfirm}
              onChange={handlePasswordConfirmChange}
              aria-invalid={Boolean(passwordConfirmError)}
              aria-describedby={
                passwordConfirmError
                  ? 'signup-password-confirm-error'
                  : passwordConfirmSuccess
                    ? 'signup-password-confirm-success'
                    : undefined
              }
              required
            />
            <button
              className="signup-password-check"
              type="button"
              onClick={validatePasswordConfirm}
            >
              일치 확인
            </button>
          </div>
          {/* 비밀번호 불일치 발생 시 사용자 안내 문구 표시. */}
          {passwordConfirmError && (
            <p className="signup-form__error" id="signup-password-confirm-error">
              {passwordConfirmError}
            </p>
          )}
          {/* 비밀번호 일치 확인 성공 시 사용자 안내 문구 표시. */}
          {passwordConfirmSuccess && (
            <p
              className="signup-form__success"
              id="signup-password-confirm-success"
              role="status"
            >
              {passwordConfirmSuccess}
            </p>
          )}

          <button type="submit">가입하기</button>
        </form>
      </section>
    </main>
  )
}

export default SignUp
