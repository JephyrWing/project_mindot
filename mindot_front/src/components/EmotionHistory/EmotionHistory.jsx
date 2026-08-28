import { useEffect, useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import { getEmotionRecords } from '../../utils/records/recordsApi.js'
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
  { value: 'intensity-high', label: '강도 높은순' },
  { value: 'intensity-low', label: '강도 낮은순' },
]

// 한 페이지에 표시할 감정 기록 개수 설정.
const recordsPerPage = 3

// 백엔드 감정 코드를 사용자에게 표시할 한국어 이름으로 변환하기 위한 목록 설정.
const emotionCodeLabels = {
  ANXIETY: '불안',
  FEAR: '두려움',
  ANGER: '분노',
  FRUSTRATION: '답답함',
  SADNESS: '슬픔',
  DISAPPOINTMENT: '실망',
  SHAME: '수치심',
  GUILT: '죄책감',
  LONELINESS: '외로움',
  JOY: '기쁨',
  RELIEF: '안도',
  ACHIEVEMENT: '성취감',
  CALM: '평온',
  GRATITUDE: '감사',
  EXCITEMENT: '설렘',
  OTHER: '기타',
}

// 백엔드 상황 코드를 사용자에게 표시할 한국어 이름으로 변환하기 위한 목록 설정.
const contextCategoryLabels = {
  SOCIAL_EVALUATION: '사회적 평가',
  PERFORMANCE: '발표·시험',
  PROMISE: '약속',
  MISTAKE: '실수',
  CONFLICT: '갈등',
  REJECTION: '거절·소외',
  WORK: '업무',
  STUDY: '학업',
  HEALTH: '건강',
  DAILY_LIFE: '일상',
  OTHER: '기타',
}

// 감정 기록 목록 API 오류 상태에 따른 사용자 안내 문구 반환.
const getHistoryErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }

  return '감정 기록 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 백엔드 목록 응답 한 건을 화면 필터와 카드에서 사용할 구조로 변환.
const normalizeEmotionRecord = (record) => ({
  id: record.emotionRecordId,
  emotionCode: record.primaryEmotionCode ?? '',
  emotion: emotionCodeLabels[record.primaryEmotionCode] ?? '분석 전',
  intensity: Number.isFinite(record.primaryIntensity)
    ? record.primaryIntensity
    : null,
  contextCode: record.contextCategory ?? '',
  context: contextCategoryLabels[record.contextCategory] ?? '미분류',
  content: record.rawText ?? '',
  occurredAt: record.occurredAt,
})

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

// 감정 기록 목록 API 결과와 탐색 기능을 제공하는 목록 화면 컴포넌트 정의.
function EmotionHistory({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onRecordDetail,
  onEmotionRecord,
  onCenter,
  onDailyCare,
  onHome,
}) {
  // 사용자가 선택한 감정 기록 조회 기간을 보관하는 상태 설정.
  const [selectedPeriod, setSelectedPeriod] = useState('all')

  // 사용자가 선택한 감정 기록 정렬 순서를 보관하는 상태 설정.
  const [sortOrder, setSortOrder] = useState('latest')

  // 사용자가 선택한 대표 감정 필터를 보관하는 상태 설정.
  const [selectedEmotion, setSelectedEmotion] = useState('all')

  // 사용자가 입력한 감정 기록 검색어를 보관하는 상태 설정.
  const [searchKeyword, setSearchKeyword] = useState('')

  // 사용자가 현재 확인 중인 감정 기록 페이지 번호 상태 설정.
  const [currentPage, setCurrentPage] = useState(1)

  // 백엔드에서 조회한 로그인 사용자의 감정 기록 목록 상태 설정.
  const [emotionRecords, setEmotionRecords] = useState([])

  // 감정 기록 목록 API 요청 진행 여부 상태 설정.
  const [isLoading, setIsLoading] = useState(true)

  // 감정 기록 목록 API 요청 실패 안내 문구 상태 설정.
  const [loadError, setLoadError] = useState('')

  // 사용자가 목록 재조회 버튼을 선택한 횟수 상태 설정.
  const [reloadCount, setReloadCount] = useState(0)

  // 화면 진입과 재조회 시 로그인 사용자의 감정 기록 목록 요청.
  useEffect(() => {
    let isActive = true

    const loadEmotionRecords = async () => {
      setIsLoading(true)
      setLoadError('')

      try {
        const records = await getEmotionRecords()

        if (isActive) {
          setEmotionRecords(records.map(normalizeEmotionRecord))
          setCurrentPage(1)
        }
      } catch (error) {
        if (isActive) {
          setEmotionRecords([])
          setLoadError(getHistoryErrorMessage(error))
        }
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadEmotionRecords()

    return () => {
      isActive = false
    }
  }, [reloadCount])

  // 선택한 기간에 해당하는 사용자 표시용 한글 문구 탐색.
  const selectedPeriodLabel = historyPeriodFilters.find(
    (filter) => filter.value === selectedPeriod,
  ).label

  // 앞뒤 공백과 대소문자 차이를 제거한 기록 검색어 생성.
  const normalizedSearchKeyword = searchKeyword.trim().toLocaleLowerCase('ko-KR')

  // 조회된 기록에 실제 포함된 대표 감정만 필터 선택 항목으로 구성.
  const historyEmotionFilters = [
    { value: 'all', label: '전체 감정' },
    ...Array.from(new Set(
      emotionRecords
        .map((record) => record.emotionCode)
        .filter(Boolean),
    )).map((emotionCode) => ({
      value: emotionCode,
      label: emotionCodeLabels[emotionCode] ?? emotionCode,
    })),
  ]

  // 선택한 기간에 해당하는 조회 기록만 남기는 필터 처리.
  const periodFilteredRecords = selectedPeriod === 'all'
    ? emotionRecords
    : emotionRecords.filter(
      (record) => new Date(record.occurredAt) >= getPeriodStartDate(selectedPeriod),
    )

  // 선택한 대표 감정과 일치하는 기록만 남기는 감정 필터 처리.
  const emotionFilteredRecords = selectedEmotion === 'all'
    ? periodFilteredRecords
    : periodFilteredRecords.filter(
      (record) => record.emotionCode === selectedEmotion,
    )

  // 내용과 감정 및 상황 중 입력한 검색어가 포함된 기록만 남기는 검색 처리.
  const filteredRecords = normalizedSearchKeyword
    ? emotionFilteredRecords.filter((record) => (
      [record.content, record.emotion, record.context].some((searchTarget) => (
        searchTarget.toLocaleLowerCase('ko-KR').includes(normalizedSearchKeyword)
      ))
    ))
    : emotionFilteredRecords

  // 선택한 정렬 기준에 따라 원본 배열을 변경하지 않고 기록 순서 정렬.
  const displayedRecords = [...filteredRecords].sort((firstRecord, secondRecord) => {
    if (sortOrder === 'intensity-high') {
      return (secondRecord.intensity ?? -1) - (firstRecord.intensity ?? -1)
    }
    if (sortOrder === 'intensity-low') {
      return (firstRecord.intensity ?? Number.POSITIVE_INFINITY)
        - (secondRecord.intensity ?? Number.POSITIVE_INFINITY)
    }

    const firstTime = new Date(firstRecord.occurredAt).getTime()
    const secondTime = new Date(secondRecord.occurredAt).getTime()

    return sortOrder === 'latest' ? secondTime - firstTime : firstTime - secondTime
  })

  // 필터링된 전체 기록을 기준으로 필요한 마지막 페이지 번호 계산.
  const totalPages = Math.max(
    1,
    Math.ceil(displayedRecords.length / recordsPerPage),
  )

  // 현재 페이지에서 화면에 표시할 감정 기록 범위 계산.
  const pageStartIndex = (currentPage - 1) * recordsPerPage
  const paginatedRecords = displayedRecords.slice(
    pageStartIndex,
    pageStartIndex + recordsPerPage,
  )

  // 선택한 기간에 따라 빈 목록의 현재 상태를 설명하는 제목 설정.
  const emptyTitle = normalizedSearchKeyword
    ? `'${searchKeyword.trim()}' 검색 결과가 없습니다.`
    : selectedEmotion !== 'all'
      ? `${emotionCodeLabels[selectedEmotion] ?? selectedEmotion} 감정 기록이 없습니다.`
    : selectedPeriod === 'all'
      ? '아직 작성한 감정 기록이 없습니다.'
      : `${selectedPeriodLabel}에 작성한 감정 기록이 없습니다.`

  // 기간 필터 변경 후 목록 첫 페이지로 이동하는 처리.
  const handlePeriodChange = (period) => {
    setSelectedPeriod(period)
    setCurrentPage(1)
  }

  // 정렬 기준 변경 후 목록 첫 페이지로 이동하는 처리.
  const handleSortChange = (event) => {
    setSortOrder(event.target.value)
    setCurrentPage(1)
  }

  // 대표 감정 필터 변경 후 목록 첫 페이지로 이동하는 처리.
  const handleEmotionChange = (event) => {
    setSelectedEmotion(event.target.value)
    setCurrentPage(1)
  }

  // 기록 검색어 변경 후 목록 첫 페이지로 이동하는 처리.
  const handleSearchKeywordChange = (event) => {
    setSearchKeyword(event.target.value)
    setCurrentPage(1)
  }

  // 입력한 기록 검색어를 비우고 목록 첫 페이지로 이동하는 처리.
  const handleSearchKeywordClear = () => {
    setSearchKeyword('')
    setCurrentPage(1)
  }

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
        onDailyCare={onDailyCare}
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
            {isLoading
              ? '불러오는 중'
              : loadError
                ? '조회 실패'
                : `${selectedPeriodLabel} ${displayedRecords.length}개`}
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
                  onClick={() => handlePeriodChange(filter.value)}
                >
                  {filter.label}
                </button>
              ))}
            </div>
          </fieldset>

          {/* 대표 감정을 기준으로 목록을 좁히는 선택 상자 배치. */}
          <label htmlFor="emotion-history-emotion">
            <span>감정</span>
            <select
              id="emotion-history-emotion"
              value={selectedEmotion}
              onChange={handleEmotionChange}
            >
              {historyEmotionFilters.map((filter) => (
                <option value={filter.value} key={filter.value}>
                  {filter.label}
                </option>
              ))}
            </select>
          </label>

          <label htmlFor="emotion-history-sort">
            <span>정렬</span>
            <select
              id="emotion-history-sort"
              value={sortOrder}
              onChange={handleSortChange}
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
                onChange={handleSearchKeywordChange}
                placeholder="내용·감정·상황에서 검색"
              />
            </label>
            <button
              type="button"
              onClick={handleSearchKeywordClear}
              disabled={!searchKeyword}
            >
              검색어 지우기
            </button>
          </div>
        </div>

        {/* 감정 기록 목록 API 요청 중 사용자에게 진행 상태 안내. */}
        {isLoading ? (
          <section
            className="emotion-history-empty"
            aria-live="polite"
            aria-busy="true"
          >
            <h2>감정 기록을 불러오는 중입니다.</h2>
            <p>잠시만 기다려 주세요.</p>
          </section>
        ) : loadError ? (
          /* 목록 API 요청 실패 시 오류 원인과 재조회 기능 안내. */
          <section className="emotion-history-empty" role="alert">
            <h2>감정 기록을 불러오지 못했습니다.</h2>
            <p>{loadError}</p>
            <button
              type="button"
              onClick={() => setReloadCount((currentCount) => currentCount + 1)}
            >
              다시 불러오기
            </button>
          </section>
        ) : displayedRecords.length > 0 ? (
          /* 선택한 기간과 정렬 순서에 맞는 감정 기록 카드 목록 배치. */
          <section
            className="emotion-history-list"
            aria-label={`${selectedPeriodLabel} 감정 기록`}
          >
            {paginatedRecords.map((record) => (
              <button
                className="emotion-history-item"
                type="button"
                key={record.id}
                onClick={() => onRecordDetail(record.id)}
                aria-label={`${record.emotion} 감정 기록 상세 보기`}
              >
                <span className="emotion-history-item-header">
                  <span className="emotion-history-item-tags">
                    <strong>{record.emotion}</strong>
                    <span>
                      {record.intensity === null
                        ? '강도 분석 전'
                        : `강도 ${record.intensity}/10`}
                    </span>
                    <span>{record.context}</span>
                  </span>
                  <time dateTime={record.occurredAt}>
                    {formatHistoryDate(record.occurredAt)}
                  </time>
                </span>
                <span className="emotion-history-item-content">
                  {record.content}
                </span>
              </button>
            ))}

            {/* 감정 기록이 한 페이지를 넘을 때 이전 및 다음 페이지 이동 기능 표시. */}
            {totalPages > 1 && (
              <nav className="emotion-history-pagination" aria-label="감정 기록 페이지">
                <button
                  type="button"
                  onClick={() => setCurrentPage((page) => page - 1)}
                  disabled={currentPage === 1}
                >
                  이전
                </button>
                <span aria-current="page">
                  {currentPage} / {totalPages}
                </span>
                <button
                  type="button"
                  onClick={() => setCurrentPage((page) => page + 1)}
                  disabled={currentPage === totalPages}
                >
                  다음
                </button>
              </nav>
            )}

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
