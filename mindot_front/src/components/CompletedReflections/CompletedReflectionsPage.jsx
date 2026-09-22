// 완료한 CBT 성찰 목록을 페이지 단위로 확인하고 결과 상세로 이동하는 화면
import Navbar from '../Navbar/Navbar.jsx'
import CompletedReflections from './CompletedReflections.jsx'
import '../CompletedReflection/CompletedReflection.css'

function CompletedReflectionsPage({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onDailyCare,
  onOpen,
  onBack,
  onHome,
}) {
  return (
    <main className="completed-reflection-page">
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
      <div className="completed-reflection-layout">
        <section className="completed-reflection-card">
          <CompletedReflections listMode onOpen={onOpen} />
          <button className="completed-reflection-back-button" type="button" onClick={onBack}>
            감정 기록 목록으로
          </button>
        </section>
      </div>
    </main>
  )
}

export default CompletedReflectionsPage
