import { useEffect, useState } from 'react'
import './ScrollToTop.css'

// 긴 화면에서 현재 스크롤 위치에 따라 맨 위 이동 버튼 표시.
function ScrollToTop() {
  const [isVisible, setIsVisible] = useState(false)

  useEffect(() => {
    const updateVisibility = () => setIsVisible(window.scrollY > 480)

    updateVisibility()
    window.addEventListener('scroll', updateVisibility, { passive: true })

    return () => window.removeEventListener('scroll', updateVisibility)
  }, [])

  if (!isVisible) return null

  return (
    <button
      className="scroll-to-top"
      type="button"
      aria-label="맨 위로 이동"
      title="맨 위로"
      onClick={() => window.scrollTo({ top: 0 })}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="m6 15 6-6 6 6" />
      </svg>
    </button>
  )
}

export default ScrollToTop
