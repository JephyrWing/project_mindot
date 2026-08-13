import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Sidebar from '../Sidebar/Sidebar.jsx'
import './Center.css'

// 화면 연결 전 사이드바 버튼 선택 시 오류를 방지하기 위한 기본 이동 처리.
const emptyNavigation = () => {}

// 관련 기관 찾기 기능의 두 번째 개발 단계를 표시하는 기본 화면 컴포넌트 정의.
function Center({
  onLogin = emptyNavigation,
  onSignUp = emptyNavigation,
  onCenter = emptyNavigation,
  onHome = emptyNavigation,
}) {
  // 공통 헤더와 간단한 검색 조건을 포함한 두 번째 단계 구조 반환.
  return (
    <main className="center-page">
      {/* 기존 공통 사이드바와 Mindot 로고를 사용하는 상단 헤더 배치. */}
      <header className="center-header">
        <Sidebar
          onLogin={onLogin}
          onSignUp={onSignUp}
          onCenter={onCenter}
          onHome={onHome}
        />
        <BrandLogo className="center-brand" onClick={onHome} />
      </header>

      {/* 관련 기관 찾기 화면의 제목과 기본 목적 안내 배치. */}
      <section className="center-content" aria-labelledby="center-title">
        <h1 id="center-title">관련 기관 찾기</h1>
        <p>가까운 마음건강 관련 기관을 확인하는 화면입니다.</p>

        {/* 실제 검색 기능 연결 전 지역과 기관 유형을 고르는 기본 조건 영역 배치. */}
        <div className="center-filter" aria-label="기관 검색 조건">
          <label htmlFor="center-region">
            <span>시·도</span>
            <select id="center-region" defaultValue="">
              <option value="" disabled>
                지역 선택
              </option>
              <option value="seoul">서울특별시</option>
              <option value="gyeonggi">경기도</option>
              <option value="gangwon">강원특별자치도</option>
              <option value="chungbuk">충청북도</option>
              <option value="chungnam">충청남도</option>
              <option value="jeonbuk">전북특별자치도</option>
              <option value="jeonnam">전라남도</option>
              <option value="gyeongbuk">경상북도</option>
              <option value="gyeongnam">경상남도</option>
            </select>
          </label>

          <label htmlFor="center-type">
            <span>기관 유형</span>
            <select id="center-type" defaultValue="">
              <option value="" disabled>
                유형 선택
              </option>
              <option value="mental-health">정신건강복지센터</option>
              <option value="counseling">심리상담센터</option>
            </select>
          </label>
        </div>
      </section>
    </main>
  )
}

export default Center
