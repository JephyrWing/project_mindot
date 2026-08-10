import './Main.css'

// 서비스명과 안내 문구를 표시하는 기본 메인 컴포넌트 정의.
function Main() {
  // 메인 화면
  return (
    <main className="main-page">
      <section className="main-card" aria-labelledby="main-title">
        <p className="main-brand">MINDOT</p>
        <h1 id="main-title">메인 페이지</h1>
        <p className="main-description">오늘의 마음을 기록해 보세요.</p>
      </section>
    </main>
  )
}

export default Main
