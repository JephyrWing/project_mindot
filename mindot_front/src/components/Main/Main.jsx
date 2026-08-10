// 별도 폴더로 분리한 사이드바 컴포넌트 불러오기.
import Sidebar from '../Sidebar/Sidebar.jsx'
// 여러 화면에서 공통으로 사용하는 Mindot 로고 불러오기.
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import './Main.css'

// 서비스명과 안내 문구를 표시하는 기본 메인 컴포넌트 정의.
function Main({ onLogin, onSignUp }) {
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
        <h1 id="main-title">메인 페이지</h1>
        <p className="main-description">오늘의 마음을 기록해 보세요.</p>
      </section>
    </main>
  )
}

export default Main
