import { useState } from 'react'
import './SignUp.css'

// 이메일의 기본 사용자명과 도메인 형식 검사 패턴 설정.
const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
// 비밀번호에서 허용할 공백 없는 영문 ASCII 문자 범위 설정.
const englishPasswordPattern = /^[\x21-\x7E]+$/

// 이메일 입력값에 따른 오류 문구 반환.
const getEmailError = (value) => {
  if (!value.trim()) return '이메일을 입력해 주세요.'
  if (!emailPattern.test(value.trim())) return '올바른 이메일 형식으로 입력해 주세요.'
  return ''
}

// 비밀번호 입력값에 따른 생성 규칙 오류 문구 반환.
const getPasswordError = (value) => {
  if (value.length < 8 || value.length > 20) {
    return '비밀번호는 8자 이상 20자 이하로 입력해 주세요.'
  }
  if (!englishPasswordPattern.test(value)) {
    return '비밀번호는 공백이나 한글 없이 영문, 숫자, 특수문자만 사용할 수 있습니다.'
  }
  if (!/[A-Z]/.test(value)) return '비밀번호에 영문 대문자를 하나 이상 포함해 주세요.'
  if (!/[a-z]/.test(value)) return '비밀번호에 영문 소문자를 하나 이상 포함해 주세요.'
  if (!/\d/.test(value)) return '비밀번호에 숫자를 하나 이상 포함해 주세요.'
  if (!/[^A-Za-z\d]/.test(value)) return '비밀번호에 특수문자를 하나 이상 포함해 주세요.'
  return ''
}

// 이메일과 비밀번호를 입력받는 기본 회원가입 컴포넌트 정의.
function SignUp() {
  // 이메일 입력값 상태 관리.
  const [email, setEmail] = useState('')
  // 이메일 형식 오류 문구 상태 관리.
  const [emailError, setEmailError] = useState('')
  // 비밀번호 입력값 상태 관리.
  const [password, setPassword] = useState('')
  // 비밀번호 규칙 오류 문구 상태 관리.
  const [passwordError, setPasswordError] = useState('')
  // 비밀번호 확인 입력값 상태 관리.
  const [passwordConfirm, setPasswordConfirm] = useState('')
  // 비밀번호 일치 여부 오류 문구 상태 관리.
  const [passwordConfirmError, setPasswordConfirmError] = useState('')
  // 비밀번호 일치 확인 성공 문구 상태 관리.
  const [passwordConfirmSuccess, setPasswordConfirmSuccess] = useState('')

  // 이메일 형식 충족 여부 계산.
  const isEmailReady = getEmailError(email) === ''
  // 비밀번호 생성 규칙 충족 여부 계산.
  const isPasswordReady = getPasswordError(password) === ''
  // 비밀번호 일치 확인까지 완료된 최종 가입 가능 여부 계산.
  const isSignUpReady = (
    isEmailReady
    && isPasswordReady
    && passwordConfirm === password
    && Boolean(passwordConfirmSuccess)
  )

  // 이메일 재입력에 따른 값 반영과 이전 오류 초기화.
  const handleEmailChange = (event) => {
    setEmail(event.target.value)
    setEmailError('')
    setPasswordConfirmError('')
    setPasswordConfirmSuccess('')
  }

  // 빈 이메일과 잘못된 이메일 형식 검사.
  const validateEmail = () => {
    const errorMessage = getEmailError(email)

    setEmailError(errorMessage)
    return errorMessage === ''
  }

  // 길이와 영문, 숫자, 특수문자 조합을 포함한 비밀번호 규칙 검사.
  const validatePassword = () => {
    const errorMessage = getPasswordError(password)

    setPasswordError(errorMessage)
    return errorMessage === ''
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
    setPasswordError('')
    setPasswordConfirmError('')
    setPasswordConfirmSuccess('')
  }

  // 실제 회원가입 API 연결 전 폼 새로고침 방지.
  const handleSubmit = (event) => {
    event.preventDefault()

    // 이메일과 비밀번호 형식 및 비밀번호 일치 여부 최종 검사.
    const isEmailValid = validateEmail()
    const isPasswordValid = validatePassword()
    const isPasswordConfirmValid = validatePasswordConfirm()

    // 하나 이상의 가입 조건 위반 시 회원가입 처리 중단.
    if (!isEmailValid || !isPasswordValid || !isPasswordConfirmValid) return
  }

  // 기본 회원가입 화면 반환.
  return (
    <main className="signup-page">
      <section className="signup-card" aria-labelledby="signup-title">
        <h1 id="signup-title">회원가입</h1>

        {/* 이메일과 비밀번호를 입력받는 기본 회원가입 폼 배치. */}
        <form className="signup-form" onSubmit={handleSubmit} noValidate>
          <label htmlFor="signup-email">이메일</label>
          <input
            id="signup-email"
            type="email"
            value={email}
            onChange={handleEmailChange}
            onBlur={validateEmail}
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
            onBlur={validatePassword}
            maxLength={20}
            placeholder={isEmailReady ? undefined : '이메일 입력 후 입력 가능'}
            disabled={!isEmailReady}
            aria-invalid={Boolean(passwordError)}
            aria-describedby={
              passwordError
                ? 'signup-password-hint signup-password-error'
                : 'signup-password-hint'
            }
            required
          />
          {/* 사용자가 지켜야 할 비밀번호 생성 규칙 안내. */}
          <p className="signup-form__hint" id="signup-password-hint">
            영문 대문자·소문자·숫자·특수문자를 모두 포함한 8~20자
          </p>
          {/* 비밀번호 규칙 위반 시 사용자 안내 문구 표시. */}
          {passwordError && (
            <p className="signup-form__error" id="signup-password-error">
              {passwordError}
            </p>
          )}

          <label htmlFor="signup-password-confirm">비밀번호 확인</label>
          {/* 비밀번호 확인 입력창과 일치 확인 버튼의 가로 배치. */}
          <div className="signup-password-confirm-row">
            <input
              id="signup-password-confirm"
              type="password"
              value={passwordConfirm}
              onChange={handlePasswordConfirmChange}
              placeholder={isPasswordReady ? undefined : '비밀번호 입력 후 입력 가능'}
              disabled={!isPasswordReady}
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
              disabled={!isPasswordReady || !passwordConfirm}
            >
            일치 여부 확인
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

          <button type="submit" disabled={!isSignUpReady}>가입하기</button>
        </form>
      </section>
    </main>
  )
}

export default SignUp
