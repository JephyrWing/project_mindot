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

// 오늘을 기준으로 목록 화면 확인용 기록 날짜를 생성하는 함수 정의.
const createPreviewDate = (daysAgo, hour) => {
  const previewDate = new Date()

  previewDate.setDate(previewDate.getDate() - daysAgo)
  previewDate.setHours(hour, 0, 0, 0)
  return previewDate.toISOString()
}

// 기록 목록 카드와 필터 동작을 확인하기 위한 임시 감정 기록 설정.
const previewEmotionRecords = [
  {
    id: 'preview-1',
    emotion: '기쁨',
    intensity: 4,
    context: '일상·기타',
    content: '오랜만에 여유로운 시간을 보내서 마음이 한결 가벼워졌다.',
    occurredAt: createPreviewDate(0, 19),
  },
  {
    id: 'preview-2',
    emotion: '불안',
    intensity: 3,
    context: '일·학업',
    content: '해야 할 일이 많아 걱정됐지만 하나씩 정리해 보기로 했다.',
    occurredAt: createPreviewDate(8, 14),
  },
  {
    id: 'preview-3',
    emotion: '평온',
    intensity: 2,
    context: '가족',
    content: '가족과 천천히 이야기를 나누며 편안한 시간을 보냈다.',
    occurredAt: createPreviewDate(40, 20),
  },
]

// 감정 기록 시각을 사용자가 읽기 쉬운 한국어 형식으로 변환하는 함수 정의.
const formatHistoryDate = (occurredAt) => new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  weekday: 'short',
  hour: '2-digit',
  minute: '2-digit',
}).format(new Date(occurredAt))

// 현재 날짜를 기준으로 선택한 기간의 시작 시각을 계산하는 함수 정의.
const getPeriodStartDate = (period) => {
  const startDate = new Date()

  startDate.setHours(0, 0, 0, 0)

  if (period === 'week') {
    const daysFromMonday = (startDate.getDay() + 6) % 7

    startDate.setDate(startDate.getDate() - daysFromMonday)
  }

  if (period === 'month') {
    startDate.setDate(1)
  }

  return startDate
}

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

  // 사용자가 입력한 감정 기록 검색어를 보관하는 상태 설정.
  const [searchKeyword, setSearchKeyword] = useState('')

  // 선택한 기간에 해당하는 사용자 표시용 한글 문구 탐색.
  const selectedPeriodLabel = historyPeriodFilters.find(
    (filter) => filter.value === selectedPeriod,
  ).label

  // 앞뒤 공백과 대소문자 차이를 제거한 기록 검색어 생성.
  const normalizedSearchKeyword = searchKeyword.trim().toLocaleLowerCase('ko-KR')

  // 선택한 기간에 해당하는 임시 기록만 남기는 필터 처리.
  const periodFilteredRecords = selectedPeriod === 'all'
    ? previewEmotionRecords
    : previewEmotionRecords.filter(
      (record) => new Date(record.occurredAt) >= getPeriodStartDate(selectedPeriod),
    )

  // 입력한 검색어가 포함된 감정 기록만 남기는 검색 처리.
  const filteredRecords = normalizedSearchKeyword
    ? periodFilteredRecords.filter((record) => (
      record.content.toLocaleLowerCase('ko-KR').includes(normalizedSearchKeyword)
    ))
    : periodFilteredRecords

  // 선택한 정렬 기준에 따라 원본 배열을 변경하지 않고 기록 순서 정렬.
  const displayedRecords = [...filteredRecords].sort((firstRecord, secondRecord) => {
    const firstTime = new Date(firstRecord.occurredAt).getTime()
    const secondTime = new Date(secondRecord.occurredAt).getTime()

    return sortOrder === 'latest' ? secondTime - firstTime : firstTime - secondTime
  })

  // 선택한 기간에 따라 빈 목록의 현재 상태를 설명하는 제목 설정.
  const emptyTitle = normalizedSearchKeyword
    ? `'${searchKeyword.trim()}' 검색 결과가 없습니다.`
    : selectedPeriod === 'all'
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
            {selectedPeriodLabel} {displayedRecords.length}개
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

          {/* 감정 기록 원문에 포함된 단어를 검색하는 입력 영역 배치. */}
          <div className="emotion-history-search">
            <label htmlFor="emotion-history-keyword">
              <span>기록 검색</span>
              <input
                id="emotion-history-keyword"
                type="search"
                value={searchKeyword}
                onChange={(event) => setSearchKeyword(event.target.value)}
                placeholder="기록 내용에서 검색"
              />
            </label>
            <button
              type="button"
              onClick={() => setSearchKeyword('')}
              disabled={!searchKeyword}
            >
              검색어 지우기
            </button>
          </div>
        </div>

        {/* 백엔드 목록 조회 연결 전 카드 구성을 확인하는 예시 기록 안내 배치. */}
        <p className="emotion-history-preview-notice">
          현재 목록 화면 확인을 위한 예시 기록입니다.
        </p>

        {/* 선택한 기간과 정렬 순서에 맞는 감정 기록 카드 목록 배치. */}
        {displayedRecords.length > 0 ? (
          <section
            className="emotion-history-list"
            aria-label={`${selectedPeriodLabel} 감정 기록`}
          >
            {displayedRecords.map((record) => (
              <article className="emotion-history-item" key={record.id}>
                <div className="emotion-history-item-header">
                  <div className="emotion-history-item-tags">
                    <strong>{record.emotion}</strong>
                    <span>강도 {record.intensity}/5</span>
                    <span>{record.context}</span>
                  </div>
                  <time dateTime={record.occurredAt}>
                    {formatHistoryDate(record.occurredAt)}
                  </time>
                </div>
                <p>{record.content}</p>
              </article>
            ))}

            {/* 목록 확인 후 새로운 감정 기록 화면으로 이동하는 버튼 배치. */}
            <button
              className="emotion-history-add-button"
              type="button"
              onClick={onEmotionRecord}
            >
              새 감정 기록하기
            </button>
          </section>
        ) : (
          /* 선택한 기간에 기록이 없을 때 사용자에게 보여 주는 빈 목록 안내. */
          <section
            className="emotion-history-empty"
            aria-labelledby="emotion-history-empty-title"
          >
            <h2 id="emotion-history-empty-title">{emptyTitle}</h2>
            <p>오늘의 마음을 기록하면 이곳에서 다시 확인할 수 있습니다.</p>
            <button type="button" onClick={onEmotionRecord}>
              감정 기록하기
            </button>
          </section>
        )}
      </section>
    </main>
  )
}

export default EmotionHistory
