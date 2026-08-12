import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import './CBT.css'

// 감정 기록을 바탕으로 생각을 돌아보는 기본 CBT 성찰 화면 컴포넌트 정의.
function CBT() {
  // 실제 성찰 기능 연결 전 제목과 간단한 시작 안내만 포함한 화면 반환.
  return (
    <main className="cbt-page">
      <section className="cbt-card" aria-labelledby="cbt-title">
        <BrandLogo className="cbt-logo" />

        <h1 id="cbt-title">CBT 성찰</h1>
        <p className="cbt-description">
          감정이 생긴 순간의 생각을 천천히 돌아보는 공간입니다.
        </p>

        {/* 사용자가 CBT 성찰의 기본 목적을 이해할 수 있는 시작 안내 배치. */}
        <div className="cbt-guide">
          <h2>생각 돌아보기</h2>
          <p>
            감정 기록을 바탕으로 당시 떠오른 생각과 새로운 관점을
            차근차근 살펴볼 수 있습니다.
          </p>
        </div>

        {/* CBT 검사 기능 연결을 위한 기본 시작 버튼 배치. */}
        <div className="cbt-actions">
          <button className="cbt-start-button" type="button">
            CBT 검사 시작하기
          </button>
        </div>
      </section>
    </main>
  )
}

export default CBT
