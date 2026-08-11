// 별도 폴더로 분리한 사이드바 컴포넌트 불러오기.
import Sidebar from '../Sidebar/Sidebar.jsx'
// 여러 화면에서 공통으로 사용하는 Mindot 로고 불러오기.
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import './Main.css'

// 서비스명과 안내 문구를 표시하는 기본 메인 컴포넌트 정의.
function Main({ onLogin, onSignUp, onEmotionRecord }) {
  // 사용자의 현재 날짜를 한국어 월·일·요일 형식으로 표시하기 위한 값 생성.
  const today = new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  }).format(new Date())

  // 햄버거 메뉴와 모바일 사이드바가 포함된 메인 화면 반환.
  return (
    <main className="main-page">
      {/* 모바일 안전 영역을 반영한 상단 헤더 배치. */}
      <header className="main-header">
        {/* 햄버거 버튼과 사이드바 동작을 포함한 독립 컴포넌트 연결. */}
        <Sidebar onLogin={onLogin} onSignUp={onSignUp} />
        <BrandLogo className="main-header__brand" />
      </header>

      <section className="main-card" aria-labelledby="main-title">
        <BrandLogo className="main-brand" />
        {/* 메인 화면에 접속한 날짜를 바로 확인할 수 있는 오늘 날짜 표시. */}
        <p className="main-today">오늘 · {today}</p>
        <h1 id="main-title">오늘의 마음은 어떤가요?</h1>
        <p className="main-description">짧은 문장으로 지금의 감정을 남겨 보세요.</p>
        {/* 감정 기록 서비스 화면으로 이동하는 버튼 배치. */}
        <button
          className="main-emotion-button"
          type="button"
          onClick={onEmotionRecord}
        >
          감정 기록하기
        </button>
      </section>
    </main>
  )
}

export default Main
