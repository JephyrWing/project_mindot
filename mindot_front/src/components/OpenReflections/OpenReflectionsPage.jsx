import Navbar from '../Navbar/Navbar.jsx'
import OpenReflections from './OpenReflections.jsx'
import '../CompletedReflection/CompletedReflection.css'

function OpenReflectionsPage({
  isAuthenticated, isLoggingOut, onLogin, onLogout, onSignUp,
  onEmotionHistory, onCenter, onDailyCare, onResume, onBack, onHome,
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
          <OpenReflections listMode onResume={onResume} />
          <button className="completed-reflection-back-button" type="button" onClick={onBack}>
            감정 기록 목록으로
          </button>
        </section>
      </div>
    </main>
  )
}

export default OpenReflectionsPage