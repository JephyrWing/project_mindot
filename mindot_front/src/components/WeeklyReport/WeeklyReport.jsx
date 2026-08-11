import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import './WeeklyReport.css'

// 주간 감정 기록 요약을 표시할 간단한 리포트 화면 컴포넌트 정의.
function WeeklyReport({ onBack }) {
  // 실제 리포트 데이터 연결 전 제목과 빈 상태만 포함한 기본 화면 반환.
  return (
    <main className="weekly-report-page">
      <section className="weekly-report-card" aria-labelledby="weekly-report-title">
        <BrandLogo className="weekly-report-logo" />

        <h1 id="weekly-report-title">주간 리포트</h1>
        <p className="weekly-report-description">
          이번 주 감정 기록을 한눈에 확인하는 공간입니다.
        </p>

        {/* 실제 주간 리포트 API 연결 전 사용자에게 보여 주는 빈 상태 안내. */}
        <div className="weekly-report-empty">
          <strong>아직 표시할 리포트가 없습니다.</strong>
          <p>감정 기록이 쌓이면 이곳에서 한 주의 흐름을 확인할 수 있습니다.</p>
        </div>

        {/* 이전 메인페이지로 돌아가기 위한 기본 이동 버튼 배치. */}
        <button type="button" onClick={onBack}>메인으로 돌아가기</button>
      </section>
    </main>
  )
}

export default WeeklyReport
