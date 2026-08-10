import './BrandLogo.css'

// 점과 연결선 로고 및 MINDOT 문구를 표시하는 공통 컴포넌트 정의.
function BrandLogo({ className = '' }) {
  // 전달받은 화면별 클래스와 공통 로고 클래스 결합.
  const logoClassName = `brand-logo${className ? ` ${className}` : ''}`

  return (
    <span className={logoClassName}>
      {/* SVG 없이 점과 연결선으로 구성한 Mindot 로고 표시. */}
      <span className="brand-logo__mark" aria-hidden="true">
        <span />
        <span />
        <span />
      </span>
      <span>MINDOT</span>
    </span>
  )
}

export default BrandLogo
