import { useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import './EmotionRecord.css'

// 감정 원문을 입력받는 기본 화면 컴포넌트 정의.
function EmotionRecord() {
  // 감정 원문 입력값 상태 관리.
  const [content, setContent] = useState('')

  // 실제 저장 기능 연결 전 폼 새로고침 방지.
  const handleSubmit = (event) => {
    event.preventDefault()
  }

  // 간단한 감정 기록 입력 화면 반환.
  return (
    <main className="emotion-record-page">
      <section className="emotion-record-card" aria-labelledby="emotion-record-title">
        <BrandLogo className="emotion-record-logo" />

        <h1 id="emotion-record-title">어떤 감정이 들었나요?</h1>

        {/* 감정 원문 입력창과 기본 버튼 배치. */}
        <form className="emotion-record-form" onSubmit={handleSubmit}>
          <label htmlFor="emotion-content">지금의 감정</label>
          <textarea
            id="emotion-content"
            value={content}
            onChange={(event) => setContent(event.target.value)}
            placeholder="지금 느끼는 감정을 작성해 주세요."
            required
          />

          <button type="submit">기록하기</button>
        </form>
      </section>
    </main>
  )
}

export default EmotionRecord
