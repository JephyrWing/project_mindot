import Navbar from '../Navbar/Navbar.jsx'
import './EmotionHistory.css'

// 감정 기록 목록 API 연결 전 기본 빈 상태를 보여 주는 목록 화면 컴포넌트 정의.
function EmotionHistory({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onEmotionRecord,
  onCenter,
  onHome,
}) {
  // 공통 네비게이션과 감정 기록 목록의 첫 단계 빈 상태 화면 반환.
  return (
    <main className="emotion-history-page">
      {/* 주요 화면 이동과 인증 메뉴를 제공하는 공통 상단 네비게이션 배치. */}
      <Navbar
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={onLogin}
        onLogout={onLogout}
        onSignUp={onSignUp}
        onEmotionHistory={onEmotionHistory}
        onCenter={onCenter}
        onHome={onHome}
      />

      {/* 감정 기록 목록의 제목과 빈 상태를 담는 기본 콘텐츠 영역 배치. */}
      <section
        className="emotion-history-content"
        aria-labelledby="emotion-history-title"
      >
        <div className="emotion-history-heading">
          <div>
            <h1 id="emotion-history-title">감정 기록 목록</h1>
            <p>지금까지 작성한 감정 기록을 확인하는 공간입니다.</p>
          </div>
          {/* 추후 조회 API 결과의 전체 기록 수를 표시할 기본 개수 문구 배치. */}
          <span className="emotion-history-count">전체 0개</span>
        </div>

        {/* 실제 기록 조회 API 연결 전 사용자에게 보여 주는 빈 목록 안내. */}
        <section
          className="emotion-history-empty"
          aria-labelledby="emotion-history-empty-title"
        >
          <span className="emotion-history-empty-mark" aria-hidden="true">+</span>
          <h2 id="emotion-history-empty-title">
            아직 작성한 감정 기록이 없습니다.
          </h2>
          <p>오늘의 마음을 기록하면 이곳에서 다시 확인할 수 있습니다.</p>
          <button type="button" onClick={onEmotionRecord}>
            첫 감정 기록하기
          </button>
        </section>
      </section>
    </main>
  )
}

export default EmotionHistory
