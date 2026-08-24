import './BrandLogo.css'

// 점과 연결선 로고 및 MINDOT 문구를 표시하는 공통 컴포넌트 정의.
function BrandLogo({ className = '', onClick }) {
  // 전달받은 화면별 클래스와 공통 로고 클래스 결합.
  const logoClassName = `brand-logo${className ? ` ${className}` : ''}`
  // 표시 요소의 종류와 관계없이 동일하게 사용할 로고 및 프로젝트명 구성.
  const logoContent = (
    <>
      {/* SVG 없이 점과 연결선으로 구성한 Mindot 로고 표시. */}
      <span className="brand-logo__mark" aria-hidden="true">
        <span />
        <span />
        <span />
      </span>
      <span>MINDOT</span>
    </>
  )

  // 메인 이동 기능이 전달된 화면에서 클릭 가능한 로고 버튼 반환.
  if (onClick) {
    return (
      <button
        className={`${logoClassName} brand-logo--button`}
        type="button"
        onClick={onClick}
        aria-label="메인페이지로 이동"
      >
        {logoContent}
      </button>
    )
  }

  // 스플래시처럼 이동 기능이 없는 화면에서 단순 표시용 로고 반환.
  return (
    <span className={logoClassName}>
      {logoContent}
    </span>
  )
}

export default BrandLogo
