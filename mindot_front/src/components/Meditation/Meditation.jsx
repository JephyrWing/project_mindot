import { useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import './Meditation.css'

// 짧은 명상 기능을 단계적으로 확장하기 위한 전용 화면 기본 구조 정의.
function Meditation({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onDailyCare,
  onBack,
  onHome,
}) {
  // 사용자의 짧은 명상 시작 여부 상태 관리.
  const [isMeditating, setIsMeditating] = useState(false)

  // 명상 시작과 마치기 상태를 하나의 버튼으로 전환하는 처리.
  const handleMeditationToggle = () => {
    setIsMeditating((currentState) => !currentState)
  }

  // 공통 네비게이션과 간단한 명상 시작 기능을 담은 이단계 화면 반환.
  return (
    <div className="meditation-page">
      {/* 다른 서비스 화면과 동일한 공통 네비게이션 배치. */}
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

      <main className="meditation-main">
        {/* 마음 돌봄 추천 화면으로 돌아가는 이전 화면 이동 버튼 배치. */}
        <button className="meditation-back-button" type="button" onClick={onBack}>
          <span aria-hidden="true">←</span>
          뒤로 돌아가기
        </button>

        {/* 짧은 명상 전용 화면의 제목과 간단한 설명 배치. */}
        <section className="meditation-content" aria-labelledby="meditation-title">
          <h1 id="meditation-title">짧은 명상</h1>
          <p>잠시 호흡에 집중하며 지금의 마음을 살펴보는 공간입니다.</p>

          {/* 명상 전 준비 방법과 현재 진행 상태를 알려 주는 영역 배치. */}
          <section className="meditation-guide" aria-labelledby="meditation-guide-title">
            <h2 id="meditation-guide-title">
              {isMeditating ? '지금의 호흡에 집중해 보세요' : '명상 준비'}
            </h2>
            <p className="meditation-guide-description">
              {isMeditating
                ? '숨을 바꾸려고 하지 말고, 들어오고 나가는 흐름을 천천히 바라보세요.'
                : '편안하게 앉은 뒤 어깨의 힘을 풀고 자연스럽게 호흡해 주세요.'}
            </p>

            {/* 명상 시작 여부를 화면 읽기 도구에도 전달하는 상태 안내 배치. */}
            <p className="meditation-status" role="status" aria-live="polite">
              {isMeditating ? '명상 진행 중' : '시작 전'}
            </p>

            <button
              className="meditation-control-button"
              type="button"
              onClick={handleMeditationToggle}
            >
              {isMeditating ? '명상 마치기' : '명상 시작하기'}
            </button>
          </section>
        </section>
      </main>
    </div>
  )
}

export default Meditation
