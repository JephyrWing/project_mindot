// 별도 폴더로 분리한 사이드바 컴포넌트 불러오기.
import Sidebar from '../Sidebar/Sidebar.jsx'
// 여러 화면에서 공통으로 사용하는 Mindot 로고 불러오기.
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import './Main.css'

// 서비스명과 안내 문구를 표시하는 기본 메인 컴포넌트 정의.
function Main({
  onLogin,
  onSignUp,
  onEmotionRecord,
  onWeeklyReport,
  onCenter,
}) {
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
        <Sidebar
          onLogin={onLogin}
          onSignUp={onSignUp}
          onCenter={onCenter}
        />
        <BrandLogo className="main-header__brand" />
      </header>

      <section className="main-card" aria-labelledby="main-title">
        <BrandLogo className="main-brand" />
        {/* 메인 화면에 접속한 날짜를 바로 확인할 수 있는 오늘 날짜 표시. */}
        <p className="main-today">오늘 · {today}</p>
        <h1 id="main-title">오늘의 마음은 어떤가요?</h1>
        <p className="main-description">짧은 문장으로 지금의 감정을 남겨 보세요.</p>

        {/* 처음 기록하는 사용자가 흐름을 이해할 수 있는 두 단계 안내 배치. */}
        <ol className="main-guide" aria-label="감정 기록 시작 방법">
          <li>
            <span aria-hidden="true">1</span>
            <div>
              <strong>감정 떠올리기</strong>
              <p>지금 가장 크게 느껴지는 마음 확인</p>
            </div>
          </li>
          <li>
            <span aria-hidden="true">2</span>
            <div>
              <strong>짧게 기록하기</strong>
              <p>떠오른 감정을 편안한 문장으로 작성</p>
            </div>
          </li>
        </ol>

        {/* 감정 기록 전에 생각을 시작할 수 있도록 돕는 오늘의 질문 배치. */}
        <aside className="main-question" aria-labelledby="main-question-title">
          <span id="main-question-title">오늘의 기록 질문</span>
          <p>오늘 가장 기억에 남는 순간은 무엇인가요?</p>
        </aside>

        {/* 감정 기록과 주간 리포트 화면으로 이동하는 기본 기능 버튼 배치. */}
        <div className="main-actions">
          <button
            className="main-emotion-button"
            type="button"
            onClick={onEmotionRecord}
          >
            감정 기록하기
          </button>
          <button
            className="main-report-button"
            type="button"
            onClick={onWeeklyReport}
          >
            주간 리포트 보기
          </button>
        </div>
      </section>
    </main>
  )
}

export default Main
