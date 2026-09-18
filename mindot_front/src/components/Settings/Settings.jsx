import { useEffect, useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import {
  getMyProfile,
  updateMyProfile,
  withdrawMyAccount,
} from '../../utils/users/usersApi.js'
import {
  getNotificationPreferences,
  updateNotificationPreferences,
} from '../../utils/notifications/notificationsApi.js'
import {
  getCurrentConsents,
  getConsentHistory,
  grantAiAnalysisConsent,
  revokeAiAnalysisConsent,
} from '../../utils/consents/consentsApi.js'
import './Settings.css'

// API 오류를 설정 화면 안내 문구로 변환
const getErrorMessage = (error, fallbackMessage) => (
  error.response?.data?.message
  ?? fallbackMessage
)

// 동의 종류와 처리 상태를 사용자에게 표시할 문구 설정.
const consentTypeLabels = {
  TERMS: '이용약관',
  PRIVACY: '개인정보 처리',
  AI_ANALYSIS: 'AI 분석',
  COUNSELOR_SHARE: '상담사 공유',
}

const consentActionLabels = {
  GRANTED: '동의',
  REVOKED: '철회',
}

// 동의 변경 시각을 한국어 날짜와 시간으로 변환.
const formatConsentHistoryDate = (occurredAt) => new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
}).format(new Date(occurredAt))

// 한 화면에 표시할 동의 변경 이력 개수 설정.
const consentHistoryPageSize = 5

// 프로필과 반복 패턴 알림 설정을 관리하는 화면
function Settings({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onDailyCare,
  onWithdrawalSuccess,
  onHome,
}) {
  // 회원 프로필과 화면 입력값 상태 관리
  const [profile, setProfile] = useState(null)
  const [displayName, setDisplayName] = useState('')

  // 반복 패턴 알림 설정 입력값 상태 관리
  const [patternAlertEnabled, setPatternAlertEnabled] = useState(false)
  const [preferredTime, setPreferredTime] = useState('09:00')
  const [timezone, setTimezone] = useState('Asia/Seoul')

  // AI 분석 동의 상태와 변경 요청 관리
  const [aiAnalysisConsent, setAiAnalysisConsent] = useState(null)
  const [isSavingAiConsent, setIsSavingAiConsent] = useState(false)
  const [isAiConsentDialogOpen, setIsAiConsentDialogOpen] = useState(false)

  // 동의 변경 이력과 페이지 조회 상태 관리.
  const [consentHistory, setConsentHistory] = useState([])
  const [consentHistoryPage, setConsentHistoryPage] = useState(0)
  const [consentHistoryInfo, setConsentHistoryInfo] = useState({
    totalPages: 0,
    totalElements: 0,
  })
  const [isConsentHistoryLoading, setIsConsentHistoryLoading] = useState(true)
  const [consentHistoryError, setConsentHistoryError] = useState('')
  const [consentHistoryReloadCount, setConsentHistoryReloadCount] = useState(0)

  // 최초 조회와 각 저장 요청의 진행 상태 관리
  const [isLoading, setIsLoading] = useState(true)
  const [isSavingProfile, setIsSavingProfile] = useState(false)
  const [isSavingNotification, setIsSavingNotification] = useState(false)

  // 오류와 저장 완료 안내 문구 관리
  const [loadError, setLoadError] = useState('')
  const [profileMessage, setProfileMessage] = useState('')
  const [profileError, setProfileError] = useState('')
  const [notificationMessage, setNotificationMessage] = useState('')
  const [notificationError, setNotificationError] = useState('')
  const [aiConsentMessage, setAiConsentMessage] = useState('')
  const [aiConsentError, setAiConsentError] = useState('')

  // 회원 탈퇴 확인창과 요청 진행 상태 관리
  const [isWithdrawalOpen, setIsWithdrawalOpen] = useState(false)
  const [isWithdrawing, setIsWithdrawing] = useState(false)
  const [withdrawalError, setWithdrawalError] = useState('')

  // 설정 화면 진입 시 프로필과 알림 설정을 함께 조회
  useEffect(() => {
    let isActive = true

    const loadSettings = async () => {
      setIsLoading(true)
      setLoadError('')

      try {
        const [
          profileResponse,
          preferencesResponse,
          consentsResponse,
        ] = await Promise.all([
          getMyProfile(),
          getNotificationPreferences(),
          getCurrentConsents(),
        ])

        if (!isActive) return

        setProfile(profileResponse)
        setDisplayName(profileResponse.displayName)
        setPatternAlertEnabled(preferencesResponse.patternAlertEnabled)
        setPreferredTime(
          preferencesResponse.preferredTime?.slice(0, 5) ?? '09:00',
        )
        setTimezone(
          preferencesResponse.timezone
          ?? profileResponse.timezone
          ?? 'Asia/Seoul',
        )
        setAiAnalysisConsent(
          consentsResponse.find(
            (consent) => consent.consentType === 'AI_ANALYSIS',
          ) ?? { granted: false, changeable: true },
        )
      } catch (error) {
        if (!isActive) return

        setLoadError(
          getErrorMessage(
            error,
            '설정 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
          ),
        )
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadSettings()

    return () => {
      isActive = false
    }
  }, [])

  // 설정 화면에서 로그인 사용자의 동의 및 철회 이력을 페이지 단위로 조회.
  useEffect(() => {
    let isActive = true

    const loadConsentHistory = async () => {
      setIsConsentHistoryLoading(true)
      setConsentHistoryError('')

      try {
        const historyResponse = await getConsentHistory({
          page: consentHistoryPage,
          size: consentHistoryPageSize,
        })

        if (!isActive) return

        setConsentHistory(historyResponse.content ?? [])
        setConsentHistoryInfo(historyResponse)
      } catch (error) {
        if (!isActive) return

        setConsentHistory([])
        setConsentHistoryError(
          getErrorMessage(
            error,
            '동의 변경 이력을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
          ),
        )
      } finally {
        if (isActive) setIsConsentHistoryLoading(false)
      }
    }

    loadConsentHistory()

    return () => {
      isActive = false
    }
  }, [consentHistoryPage, consentHistoryReloadCount])

  // 입력한 닉네임을 백엔드 프로필에 반영
  const handleProfileSubmit = async (event) => {
    event.preventDefault()

    if (isSavingProfile) return

    setIsSavingProfile(true)
    setProfileMessage('')
    setProfileError('')

    try {
      const updatedProfile = await updateMyProfile(displayName.trim())

      setProfile(updatedProfile)
      setDisplayName(updatedProfile.displayName)
      setProfileMessage('닉네임을 변경했습니다.')
    } catch (error) {
      setProfileError(
        getErrorMessage(
          error,
          '닉네임을 변경하지 못했습니다. 잠시 후 다시 시도해 주세요.',
        ),
      )
    } finally {
      setIsSavingProfile(false)
    }
  }

  // 반복 패턴 알림 수신 여부와 희망 시각을 백엔드에 반영
  const handleNotificationSubmit = async (event) => {
    event.preventDefault()

    if (isSavingNotification) return

    setIsSavingNotification(true)
    setNotificationMessage('')
    setNotificationError('')

    try {
      const updatedPreferences = await updateNotificationPreferences({
        patternAlertEnabled,
        preferredTime: `${preferredTime}:00`,
      })

      setPatternAlertEnabled(updatedPreferences.patternAlertEnabled)
      setPreferredTime(
        updatedPreferences.preferredTime?.slice(0, 5) ?? preferredTime,
      )
      setTimezone(updatedPreferences.timezone ?? timezone)
      setNotificationMessage('알림 설정을 저장했습니다.')
    } catch (error) {
      setNotificationError(
        getErrorMessage(
          error,
          '알림 설정을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.',
        ),
      )
    } finally {
      setIsSavingNotification(false)
    }
  }

  // AI 분석 동의 철회 또는 재동의 처리
  const handleAiConsentChange = async (granted) => {
    if (isSavingAiConsent) return

    setIsSavingAiConsent(true)
    setAiConsentMessage('')
    setAiConsentError('')

    try {
      const updatedConsent = granted
        ? await grantAiAnalysisConsent()
        : await revokeAiAnalysisConsent()

      setAiAnalysisConsent(updatedConsent)
      setConsentHistoryPage(0)
      setConsentHistoryReloadCount((currentCount) => currentCount + 1)
      setAiConsentMessage(
        granted
          ? 'AI 분석에 다시 동의했습니다.'
          : 'AI 분석 동의를 철회했습니다.',
      )
      setIsAiConsentDialogOpen(false)
    } catch (error) {
      setAiConsentError(
        getErrorMessage(
          error,
          'AI 분석 동의 상태를 변경하지 못했습니다. 잠시 후 다시 시도해 주세요.',
        ),
      )
      setIsAiConsentDialogOpen(false)
    } finally {
      setIsSavingAiConsent(false)
    }
  }

  // 확인 절차를 통과한 회원 탈퇴 요청 처리
  const handleWithdrawal = async () => {
    if (isWithdrawing) return

    setIsWithdrawing(true)
    setWithdrawalError('')

    try {
      await withdrawMyAccount()
      onWithdrawalSuccess()
    } catch (error) {
      setWithdrawalError(
        getErrorMessage(
          error,
          '회원 탈퇴를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.',
        ),
      )
      setIsWithdrawalOpen(false)
      setIsWithdrawing(false)
    }
  }

  return (
    <main className="settings-page">
      <Navbar
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={onLogin}
        onLogout={onLogout}
        onSignUp={onSignUp}
        onEmotionHistory={onEmotionHistory}
        onCenter={onCenter}
        onDailyCare={onDailyCare}
        onHome={onHome}
      />

      <div className="settings-content">
        <header className="settings-heading">
          <h1>설정</h1>
          <p>내 프로필, AI 분석 동의와 반복 패턴 알림을 관리할 수 있습니다.</p>
        </header>

        {isLoading ? (
          <section className="settings-state" role="status">
            설정 정보를 불러오고 있습니다.
          </section>
        ) : loadError ? (
          <section className="settings-state settings-state--error" role="alert">
            <strong>설정 정보를 불러오지 못했습니다.</strong>
            <p>{loadError}</p>
            <button
              type="button"
              onClick={() => window.location.reload()}
            >
              다시 불러오기
            </button>
          </section>
        ) : (
          <>
            {/* 이메일 확인과 닉네임 변경 영역 */}
            <section
              className="settings-card"
              aria-labelledby="profile-settings-title"
            >
              <div className="settings-card__heading">
                <h2 id="profile-settings-title">회원 프로필</h2>
                <p>서비스에서 사용할 닉네임을 관리합니다.</p>
              </div>

              <form
                className="settings-form"
                onSubmit={handleProfileSubmit}
              >
                <label htmlFor="settings-email">이메일</label>
                <input
                  id="settings-email"
                  type="email"
                  value={profile?.email ?? ''}
                  readOnly
                />
                <p className="settings-field-help">
                  로그인 계정 이메일은 이 화면에서 변경할 수 없습니다.
                </p>

                <label htmlFor="settings-display-name">닉네임</label>
                <input
                  id="settings-display-name"
                  type="text"
                  value={displayName}
                  maxLength={80}
                  autoComplete="nickname"
                  onChange={(event) => setDisplayName(event.target.value)}
                />

                <button
                  className="settings-submit"
                  type="submit"
                  disabled={isSavingProfile || !displayName.trim()}
                >
                  {isSavingProfile ? '저장 중…' : '닉네임 저장'}
                </button>

                {profileMessage && (
                  <p className="settings-message settings-message--success" role="status">
                    {profileMessage}
                  </p>
                )}
                {profileError && (
                  <p className="settings-message settings-message--error" role="alert">
                    {profileError}
                  </p>
                )}
              </form>
            </section>

            {/* AI 분석 동의 상태와 변경 기능 영역 */}
            <section
              className="settings-card"
              aria-labelledby="ai-consent-settings-title"
            >
              <div className="settings-card__heading">
                <h2 id="ai-consent-settings-title">AI 분석 동의</h2>
                <p>
                  감정 기록 분석과 CBT 성찰 등 AI 기능 사용 여부를 관리합니다.
                </p>
              </div>

              <div className="settings-consent-form">
                <label className="settings-toggle">
                  <input
                    type="checkbox"
                    checked={Boolean(aiAnalysisConsent?.granted)}
                    disabled={isSavingAiConsent || !aiAnalysisConsent?.changeable}
                    onChange={(event) => {
                      if (event.target.checked) {
                        handleAiConsentChange(true)
                      } else {
                        setAiConsentMessage('')
                        setAiConsentError('')
                        setIsAiConsentDialogOpen(true)
                      }
                    }}
                  />
                  <span>
                    <strong>AI 분석 사용</strong>
                    <small>
                      {aiAnalysisConsent?.granted
                        ? '감정 분석과 CBT 등 AI 기능을 사용할 수 있습니다.'
                        : '새로운 감정 AI 분석과 CBT 기능이 제한됩니다.'}
                    </small>
                  </span>
                </label>

                {isSavingAiConsent && (
                  <p className="settings-consent-progress" role="status">
                    동의 상태를 변경하고 있습니다.
                  </p>
                )}
              </div>

              {aiConsentMessage && (
                <p className="settings-message settings-message--success" role="status">
                  {aiConsentMessage}
                </p>
              )}
              {aiConsentError && (
                <p className="settings-message settings-message--error" role="alert">
                  {aiConsentError}
                </p>
              )}
            </section>

            {/* 이용약관과 개인정보 및 AI 분석 동의 변경 이력 조회 영역 */}
            <section
              className="settings-card"
              aria-labelledby="consent-history-title"
            >
              <div className="settings-card__heading settings-consent-history-heading">
                <div>
                  <h2 id="consent-history-title">동의 변경 이력</h2>
                  <p>서비스 이용 중 동의하거나 철회한 내역을 확인합니다.</p>
                </div>
                {!isConsentHistoryLoading && !consentHistoryError && (
                  <span>전체 {consentHistoryInfo.totalElements ?? 0}건</span>
                )}
              </div>

              {isConsentHistoryLoading ? (
                <p className="settings-consent-history-state" role="status">
                  동의 변경 이력을 불러오고 있습니다.
                </p>
              ) : consentHistoryError ? (
                <div className="settings-consent-history-state" role="alert">
                  <p>{consentHistoryError}</p>
                  <button
                    type="button"
                    onClick={() => setConsentHistoryReloadCount(
                      (currentCount) => currentCount + 1,
                    )}
                  >
                    다시 불러오기
                  </button>
                </div>
              ) : consentHistory.length === 0 ? (
                <p className="settings-consent-history-state">
                  표시할 동의 변경 이력이 없습니다.
                </p>
              ) : (
                <>
                  <ul className="settings-consent-history-list">
                    {consentHistory.map((history) => (
                      <li key={history.consentEventId}>
                        <div>
                          <strong>
                            {consentTypeLabels[history.consentType]
                              ?? history.consentType}
                          </strong>
                          <span>
                            {history.consentVersion
                              ? `버전 ${history.consentVersion}`
                              : '버전 정보 없음'}
                          </span>
                        </div>
                        <div className="settings-consent-history-result">
                          <span className={`is-${history.action?.toLowerCase()}`}>
                            {consentActionLabels[history.action] ?? history.action}
                          </span>
                          <time dateTime={history.occurredAt}>
                            {formatConsentHistoryDate(history.occurredAt)}
                          </time>
                        </div>
                      </li>
                    ))}
                  </ul>

                  {consentHistoryInfo.totalPages > 1 && (
                    <nav
                      className="settings-consent-history-pagination"
                      aria-label="동의 변경 이력 페이지"
                    >
                      <button
                        type="button"
                        disabled={consentHistoryPage === 0}
                        onClick={() => setConsentHistoryPage((page) => page - 1)}
                      >
                        이전
                      </button>
                      <span aria-current="page">
                        {consentHistoryPage + 1} / {consentHistoryInfo.totalPages}
                      </span>
                      <button
                        type="button"
                        disabled={consentHistoryPage + 1 >= consentHistoryInfo.totalPages}
                        onClick={() => setConsentHistoryPage((page) => page + 1)}
                      >
                        다음
                      </button>
                    </nav>
                  )}
                </>
              )}
            </section>

            {/* 반복 패턴 알림 수신 여부와 희망 시각 설정 영역 */}
            <section
              className="settings-card"
              aria-labelledby="notification-settings-title"
            >
              <div className="settings-card__heading">
                <h2 id="notification-settings-title">반복 패턴 알림</h2>
                <p>
                  최근 8주의 감정 기록에서 반복 패턴이 발견되면
                  앱 안에서 알려드립니다.
                </p>
              </div>

              <form
                className="settings-form"
                onSubmit={handleNotificationSubmit}
              >
                <label className="settings-toggle">
                  <input
                    type="checkbox"
                    checked={patternAlertEnabled}
                    onChange={(event) => {
                      setPatternAlertEnabled(event.target.checked)
                    }}
                  />
                  <span>
                    <strong>반복 패턴 알림 받기</strong>
                    <small>
                      알림을 끄면 새로운 패턴 알림을 생성하지 않습니다.
                    </small>
                  </span>
                </label>

                <label htmlFor="settings-preferred-time">
                  알림 희망 시각
                </label>
                <input
                  id="settings-preferred-time"
                  type="time"
                  value={preferredTime}
                  disabled={!patternAlertEnabled}
                  onChange={(event) => setPreferredTime(event.target.value)}
                />
                <p className="settings-field-help">
                  기준 시간대: {timezone}
                </p>

                <button
                  className="settings-submit"
                  type="submit"
                  disabled={isSavingNotification}
                >
                  {isSavingNotification ? '저장 중…' : '알림 설정 저장'}
                </button>

                {notificationMessage && (
                  <p className="settings-message settings-message--success" role="status">
                    {notificationMessage}
                  </p>
                )}
                {notificationError && (
                  <p className="settings-message settings-message--error" role="alert">
                    {notificationError}
                  </p>
                )}
              </form>
            </section>

            {/* 회원 탈퇴 확인창을 여는 계정 관리 영역 */}
            <section
              className="settings-card settings-danger-card"
              aria-labelledby="account-settings-title"
            >
              <div className="settings-withdrawal">
                <div>
                  <strong id="account-settings-title">회원 탈퇴</strong>
                  <p>
                    탈퇴하면 현재 계정으로 서비스를 이용할 수 없습니다.
                  </p>
                </div>

                <button
                  className="settings-withdrawal-button"
                  type="button"
                  onClick={() => {
                    setWithdrawalError('')
                    setIsWithdrawalOpen(true)
                  }}
                >
                  탈퇴하기
                </button>
              </div>

              {withdrawalError && (
                <p
                  className="settings-message settings-message--error"
                  role="alert"
                >
                  {withdrawalError}
                </p>
              )}
            </section>
          </>
        )}
      </div>

      {/* AI 분석 동의 철회 전 제한 기능 확인 */}
      {isAiConsentDialogOpen && (
        <div
          className="settings-dialog-backdrop"
          role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget && !isSavingAiConsent) {
              setIsAiConsentDialogOpen(false)
            }
          }}
        >
          <section
            className="settings-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="ai-consent-dialog-title"
            aria-describedby="ai-consent-dialog-description"
          >
            <h2 id="ai-consent-dialog-title">AI 분석 동의를 철회할까요?</h2>
            <p id="ai-consent-dialog-description">
              철회 후에는 새로운 감정 분석, CBT 성찰 및 검색 연결 기능을
              사용할 수 없습니다. 기존에 저장된 기록은 유지됩니다.
            </p>

            <div className="settings-dialog-actions">
              <button
                className="settings-dialog-cancel"
                type="button"
                disabled={isSavingAiConsent}
                onClick={() => setIsAiConsentDialogOpen(false)}
              >
                취소
              </button>
              <button
                className="settings-dialog-consent-confirm"
                type="button"
                disabled={isSavingAiConsent}
                onClick={() => handleAiConsentChange(false)}
              >
                {isSavingAiConsent ? '처리 중…' : '동의 철회'}
              </button>
            </div>
          </section>
        </div>
      )}

      {/* 실수로 탈퇴하지 않도록 최종 확인 단계 제공 */}
      {isWithdrawalOpen && (
        <div
          className="settings-dialog-backdrop"
          role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget && !isWithdrawing) {
              setIsWithdrawalOpen(false)
            }
          }}
        >
          <section
            className="settings-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="withdrawal-dialog-title"
            aria-describedby="withdrawal-dialog-description"
          >
            <h2 id="withdrawal-dialog-title">정말 탈퇴하시겠습니까?</h2>
            <p id="withdrawal-dialog-description">
              탈퇴하면 계정과 저장된 기록이 삭제되며 복구할 수 없습니다.
              같은 로그인 수단으로 다시 가입해도 이전 기록은 돌아오지 않습니다.
            </p>

            <div className="settings-dialog-actions">
              <button
                className="settings-dialog-cancel"
                type="button"
                disabled={isWithdrawing}
                onClick={() => setIsWithdrawalOpen(false)}
              >
                취소
              </button>
              <button
                className="settings-dialog-confirm"
                type="button"
                disabled={isWithdrawing}
                onClick={handleWithdrawal}
              >
                {isWithdrawing ? '탈퇴 처리 중…' : '회원 탈퇴'}
              </button>
            </div>
          </section>
        </div>
      )}
    </main>
  )
}

export default Settings
