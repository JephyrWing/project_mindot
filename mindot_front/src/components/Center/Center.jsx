import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Sidebar from '../Sidebar/Sidebar.jsx'
import './Center.css'

// 화면 연결 전 사이드바 버튼 선택 시 오류를 방지하기 위한 기본 이동 처리.
const emptyNavigation = () => {}

// 관련 기관 찾기 기능의 첫 번째 개발 단계를 표시하는 기본 화면 컴포넌트 정의.
function Center({
  onLogin = emptyNavigation,
  onSignUp = emptyNavigation,
  onCenter = emptyNavigation,
}) {
  // 공통 헤더와 간단한 화면 안내만 포함한 첫 번째 단계 구조 반환.
  return (
    <main className="center-page">
      {/* 기존 공통 사이드바와 Mindot 로고를 사용하는 상단 헤더 배치. */}
      <header className="center-header">
        <Sidebar
          onLogin={onLogin}
          onSignUp={onSignUp}
          onCenter={onCenter}
        />
        <BrandLogo className="center-brand" />
      </header>

      {/* 관련 기관 찾기 화면의 제목과 기본 목적 안내 배치. */}
      <section className="center-content" aria-labelledby="center-title">
        <h1 id="center-title">관련 기관 찾기</h1>
        <p>가까운 마음건강 관련 기관을 확인하는 화면입니다.</p>
      </section>
    </main>
  )
}

export default Center
