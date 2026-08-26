import Navbar from '../Navbar/Navbar.jsx'
import './DailyCare.css'

// 최근 감정 기록을 바탕으로 하루의 마음 돌봄 활동을 제안하는 화면 정의.
function DailyCare({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onDailyCare,
  onHome,
  onBreathing,
  onMeditation,
  onCBT,
  onHelpful,
  onLater,
}) {
  // 추천 안내와 세 가지 마음 돌봄 활동으로 구성한 기본 화면 반환.
  return (
    <div className="daily-care-page">
      {/* 다른 서비스 화면과 동일한 크기와 기능의 공통 네비게이션 배치. */}
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

      <main className="daily-care-main">
        {/* 화면 목적과 추천 기준을 설명하는 도입 영역. */}
        <section className="daily-care-intro" aria-labelledby="daily-care-title">
          <h1 id="daily-care-title">오늘 밤, 마음을 돌볼 시간이에요</h1>
          <p>최근 기록에서 밤 시간대의 지친 마음이 반복되었어요.</p>
          <dl className="daily-care-basis">
            <dt>추천 기준</dt>
            <dd>최근 7일 감정 기록</dd>
          </dl>
        </section>

        {/* 오늘 가장 먼저 시도할 활동을 알려 주는 추천 영역. */}
        <section className="daily-care-suggestion" aria-labelledby="daily-care-suggestion-title">
          <h2 id="daily-care-suggestion-title">오늘의 제안</h2>
          <p>잠들기 전 3분 호흡으로 하루를 천천히 정리해 보세요.</p>
        </section>

        {/* 추후 기능 연결을 위한 마음 돌봄 활동 버튼 목록. */}
        <section className="daily-care-actions" aria-label="마음 돌봄 활동">
          <article className="daily-care-action">
            <div>
              <h2>3분 호흡 시작</h2>
              <p>짧은 호흡으로 지금의 마음을 가라앉혀요.</p>
            </div>
            <button
              className="daily-care-primary-button"
              type="button"
              onClick={onBreathing}
            >
              시작하기
            </button>
          </article>

          <article className="daily-care-action">
            <div>
              <h2>짧은 명상 듣기</h2>
              <p>오늘의 마음에 맞는 짧은 콘텐츠를 확인해요.</p>
            </div>
            <button type="button" onClick={onMeditation}>
              명상 추천
            </button>
          </article>

          <article className="daily-care-action">
            <div>
              <h2>CBT 성찰 이어하기</h2>
              <p>기록했던 생각을 대화로 차분하게 돌아봐요.</p>
            </div>
            <button type="button" onClick={onCBT}>
              이어서 하기
            </button>
          </article>
        </section>

        {/* 추천 만족도 기능 연결을 위한 간단한 선택 영역. */}
        <section className="daily-care-feedback" aria-labelledby="daily-care-feedback-title">
          <h2 id="daily-care-feedback-title">오늘의 제안이 도움이 되었나요?</h2>
          <div className="daily-care-feedback-actions">
            <button type="button" onClick={onHelpful}>도움됐어요</button>
            <button type="button" onClick={onLater}>다음에 추천해요</button>
          </div>
        </section>
      </main>
    </div>
  )
}

export default DailyCare
