import { useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import './EmotionHistory.css'

// 감정 기록 조회 범위를 선택하기 위한 기간 필터 목록 설정.
const historyPeriodFilters = [
  { value: 'all', label: '전체' },
  { value: 'week', label: '이번 주' },
  { value: 'month', label: '이번 달' },
]

// 감정 기록의 표시 순서를 선택하기 위한 정렬 목록 설정.
const historySortOptions = [
  { value: 'latest', label: '최신순' },
  { value: 'oldest', label: '오래된순' },
]

// 감정 기록 목록 API 연결 전 기본 빈 상태를 보여 주는 목록 화면 컴포넌트 정의.
function EmotionHistory({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onEmotionRecord,
  onCenter,
  onHome,
}) {
  // 사용자가 선택한 감정 기록 조회 기간을 보관하는 상태 설정.
  const [selectedPeriod, setSelectedPeriod] = useState('all')

  // 사용자가 선택한 감정 기록 정렬 순서를 보관하는 상태 설정.
  const [sortOrder, setSortOrder] = useState('latest')

  // 선택한 기간에 해당하는 사용자 표시용 한글 문구 탐색.
  const selectedPeriodLabel = historyPeriodFilters.find(
    (filter) => filter.value === selectedPeriod,
  ).label

  // 선택한 기간에 따라 빈 목록의 현재 상태를 설명하는 제목 설정.
  const emptyTitle = selectedPeriod === 'all'
    ? '아직 작성한 감정 기록이 없습니다.'
    : `${selectedPeriodLabel}에 작성한 감정 기록이 없습니다.`

  // 공통 네비게이션과 감정 기록 목록의 두 번째 단계 탐색 화면 반환.
  return (
    <main className="emotion-history-page">
      {/* 주요 화면 이동과 인증 메뉴를 제공하는 공통 상단 네비게이션 배치. */}
      <Navbar
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={onLogin}
        onLogout={onLogout}
        onSignUp={onSignUp}
        onEmotionHistory={onEmotionHistory}
        onCenter={onCenter}
        onHome={onHome}
      />

      {/* 감정 기록 목록의 제목과 빈 상태를 담는 기본 콘텐츠 영역 배치. */}
      <section
        className="emotion-history-content"
        aria-labelledby="emotion-history-title"
      >
        <div className="emotion-history-heading">
          <div>
            <h1 id="emotion-history-title">감정 기록 목록</h1>
            <p>지금까지 작성한 감정 기록을 확인하는 공간입니다.</p>
          </div>
          {/* 선택한 기간의 조회 결과 개수를 표시할 기본 개수 문구 배치. */}
          <span className="emotion-history-count">
            {selectedPeriodLabel} 0개
          </span>
        </div>

        {/* 감정 기록의 조회 기간과 표시 순서를 선택하는 탐색 영역 배치. */}
        <div className="emotion-history-controls">
          <fieldset>
            <legend>기간 선택</legend>
            <div className="emotion-history-filter-buttons">
              {historyPeriodFilters.map((filter) => (
                <button
                  className={selectedPeriod === filter.value ? 'is-selected' : ''}
                  type="button"
                  key={filter.value}
                  aria-pressed={selectedPeriod === filter.value}
                  onClick={() => setSelectedPeriod(filter.value)}
                >
                  {filter.label}
                </button>
              ))}
            </div>
          </fieldset>

          <label htmlFor="emotion-history-sort">
            <span>정렬</span>
            <select
              id="emotion-history-sort"
              value={sortOrder}
              onChange={(event) => setSortOrder(event.target.value)}
            >
              {historySortOptions.map((option) => (
                <option value={option.value} key={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
        </div>

        {/* 실제 기록 조회 API 연결 전 사용자에게 보여 주는 빈 목록 안내. */}
        <section
          className="emotion-history-empty"
          aria-labelledby="emotion-history-empty-title"
        >
          <span className="emotion-history-empty-mark" aria-hidden="true">+</span>
          <h2 id="emotion-history-empty-title">{emptyTitle}</h2>
          <p>오늘의 마음을 기록하면 이곳에서 다시 확인할 수 있습니다.</p>
          <button type="button" onClick={onEmotionRecord}>
            첫 감정 기록하기
          </button>
        </section>
      </section>
    </main>
  )
}

export default EmotionHistory
