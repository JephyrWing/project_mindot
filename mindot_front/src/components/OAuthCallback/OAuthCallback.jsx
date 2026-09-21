import { useEffect, useMemo, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import {
  completeSocialLogin,
  completeSocialSignup,
} from '../../utils/auth/authApi.js'
import './OAuthCallback.css'

// 개발 모드 중복 렌더링에서도 일회용 인가 코드를 한 번만 전송하기 위한 요청 보관소 설정.
const callbackRequests = new Map()

// 소셜 제공자 코드의 사용자 표시용 이름 목록 설정.
const providerLabels = {
  kakao: '카카오',
  google: 'Google',
}

// 현재 실행 환경에 맞는 소셜 로그인 콜백 주소 생성.
const createRedirectUri = (provider) => {
  const configuredOrigin = import.meta.env?.VITE_OAUTH_REDIRECT_ORIGIN
    || window.location.origin
  const redirectOrigin = configuredOrigin.replace(/\/$/, '')

  return `${redirectOrigin}/oauth/${provider}/callback`
}

// 같은 인가 코드와 state 조합의 콜백 요청을 한 번만 실행하는 처리.
const completeSocialLoginOnce = (provider, callbackData) => {
  const requestKey = `${provider}:${callbackData.code}:${callbackData.state}`

  if (!callbackRequests.has(requestKey)) {
    callbackRequests.set(
      requestKey,
      completeSocialLogin(provider, callbackData),
    )
  }

  return callbackRequests.get(requestKey)
}

// 소셜 로그인 콜백 오류를 사용자가 다시 시도할 수 있는 안내 문구로 변환.
const getCallbackErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 로그인 화면에서 다시 시도해 주세요.'
  }

  if (error.response.status === 401) {
    return '소셜 로그인 요청이 만료되었거나 유효하지 않습니다. 다시 시도해 주세요.'
  }

  return error.response.data?.message
    || error.response.data?.detail
    || '소셜 로그인을 완료하지 못했습니다. 다시 시도해 주세요.'
}

// 소셜 제공자 콜백 완료와 신규 회원 필수 동의를 처리하는 화면 정의.
function OAuthCallback({ provider, onLoginSuccess, onLogin, onHome }) {
  // 최초 콜백 주소의 제공자와 쿼리 값을 검증한 요청 정보 설정.
  const callbackInput = useMemo(() => {
    const callbackParams = new URLSearchParams(window.location.search)
    const providerError = callbackParams.get('error')
    const code = callbackParams.get('code')
    const state = callbackParams.get('state')

    if (!providerLabels[provider]) {
      return { error: '지원하지 않는 소셜 로그인 경로입니다.' }
    }
    if (providerError) {
      return { error: '소셜 로그인이 취소되었습니다. 로그인 화면에서 다시 시도해 주세요.' }
    }
    if (!code || !state) {
      return { error: '소셜 로그인 확인 정보가 없습니다. 다시 시도해 주세요.' }
    }

    return {
      callbackData: {
        code,
        state,
        redirectUri: createRedirectUri(provider),
      },
    }
  }, [provider])
  // 콜백 확인과 신규 회원 동의 단계를 구분하는 화면 상태 관리.
  const [callbackStatus, setCallbackStatus] = useState(
    callbackInput.error ? 'error' : 'processing',
  )
  // 콜백 또는 소셜 회원가입 실패 안내 문구 상태 관리.
  const [callbackError, setCallbackError] = useState(callbackInput.error ?? '')
  // 신규 소셜 회원에게 표시할 제공자 프로필 상태 관리.
  const [socialProfile, setSocialProfile] = useState(null)
  // 신규 소셜 회원가입에 사용할 일회용 가입 티켓 상태 관리.
  const [signupTicket, setSignupTicket] = useState('')
  // 이용약관 필수 동의 상태 관리.
  const [termsAgreed, setTermsAgreed] = useState(false)
  // 개인정보 처리 필수 동의 상태 관리.
  const [privacyAgreed, setPrivacyAgreed] = useState(false)
  // AI 분석 필수 동의 상태 관리.
  const [aiAnalysisAgreed, setAiAnalysisAgreed] = useState(false)
  // 신규 소셜 회원가입 요청 진행 상태 관리.
  const [isSubmitting, setIsSubmitting] = useState(false)
  const providerLabel = providerLabels[provider] ?? '소셜'
  const areAgreementsReady = termsAgreed && privacyAgreed && aiAnalysisAgreed

  // 제공자가 전달한 인가 코드와 state를 백엔드에서 검증하고 다음 단계 결정.
  useEffect(() => {
    if (!callbackInput.callbackData) return undefined

    let isActive = true

    const verifyCallback = async () => {
      try {
        const result = await completeSocialLoginOnce(
          provider,
          callbackInput.callbackData,
        )

        if (!isActive) return

        if (result.signupRequired) {
          if (!result.signupTicket) {
            throw new Error('소셜 회원가입 티켓이 없습니다.')
          }

          setSocialProfile({
            email: result.email,
            displayName: result.displayName,
          })
          setSignupTicket(result.signupTicket)
          setCallbackStatus('signup-required')
          return
        }

        onLoginSuccess()
      } catch (error) {
        if (!isActive) return

        setCallbackStatus('error')
        setCallbackError(getCallbackErrorMessage(error))
      }
    }

    verifyCallback()

    return () => {
      isActive = false
    }
  }, [callbackInput, onLoginSuccess, provider])

  // 신규 소셜 회원의 필수 동의와 가입 티켓을 백엔드에 제출하는 처리.
  const handleSocialSignup = async (event) => {
    event.preventDefault()

    if (!areAgreementsReady || !signupTicket || isSubmitting) return

    setIsSubmitting(true)
    setCallbackError('')

    try {
      await completeSocialSignup(signupTicket, {
        termsAgreed,
        privacyAgreed,
        aiAnalysisAgreed,
      })
      onLoginSuccess()
    } catch (error) {
      setCallbackError(getCallbackErrorMessage(error))
      setIsSubmitting(false)
    }
  }

  // 콜백 진행 상태와 신규 회원 동의 폼을 포함한 단일 카드 화면 반환.
  return (
    <main className="oauth-callback-page">
      <section className="oauth-callback-card" aria-labelledby="oauth-callback-title">
        <BrandLogo className="oauth-callback-logo" onClick={onHome} />

        {callbackStatus === 'processing' && (
          <div className="oauth-callback-status" aria-live="polite">
            <h1 id="oauth-callback-title">소셜 로그인 확인 중</h1>
            <p>{providerLabel}에서 받은 로그인 정보를 안전하게 확인하고 있습니다.</p>
          </div>
        )}

        {callbackStatus === 'signup-required' && (
          <>
            <header className="oauth-callback-header">
              <h1 id="oauth-callback-title">소셜 회원가입</h1>
              <p>{providerLabel} 인증이 완료되었습니다. 필수 동의 후 바로 시작할 수 있습니다.</p>
            </header>

            {/* 제공자에서 확인한 이메일과 이름을 수정 불가능한 기본 정보로 표시. */}
            <dl className="oauth-profile">
              <div>
                <dt>이메일</dt>
                <dd>{socialProfile?.email || '제공자에서 확인되지 않음'}</dd>
              </div>
              <div>
                <dt>닉네임</dt>
                <dd>{socialProfile?.displayName || '소셜 회원'}</dd>
              </div>
            </dl>

            <form className="oauth-consent-form" onSubmit={handleSocialSignup}>
              <fieldset disabled={isSubmitting}>
                <legend>필수 동의</legend>
                <label>
                  <input
                    type="checkbox"
                    checked={termsAgreed}
                    onChange={(event) => setTermsAgreed(event.target.checked)}
                  />
                  <span>이용약관 동의</span>
                </label>
                <label>
                  <input
                    type="checkbox"
                    checked={privacyAgreed}
                    onChange={(event) => setPrivacyAgreed(event.target.checked)}
                  />
                  <span>개인정보 처리 동의</span>
                </label>
                <label>
                  <input
                    type="checkbox"
                    checked={aiAnalysisAgreed}
                    onChange={(event) => setAiAnalysisAgreed(event.target.checked)}
                  />
                  <span>AI 분석 동의</span>
                </label>
              </fieldset>
              <p className="oauth-consent-documents">
                가입 전에 <a href="/terms" target="_blank" rel="noreferrer">이용약관</a>과{' '}
                <a href="/privacy" target="_blank" rel="noreferrer">개인정보 처리방침</a>을 확인해 주세요.
              </p>

              {callbackError && <p className="oauth-callback-error" role="alert">{callbackError}</p>}

              <button type="submit" disabled={!areAgreementsReady || isSubmitting}>
                {isSubmitting ? '가입 중...' : '동의하고 시작하기'}
              </button>
            </form>
          </>
        )}

        {callbackStatus === 'error' && (
          <div className="oauth-callback-status" role="alert">
            <h1 id="oauth-callback-title">소셜 로그인을 완료하지 못했습니다</h1>
            <p>{callbackError}</p>
            <button type="button" onClick={onLogin}>로그인 화면으로 돌아가기</button>
          </div>
        )}
      </section>
    </main>
  )
}

export default OAuthCallback
