import { useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import { searchCenters } from '../../utils/centers/centersApi.js'
import {
  getDistrictNames,
  getTownNames,
  regionNames,
} from './regionData.js'
import './Center.css'

// 화면 연결 전 사이드바 버튼 선택 시 오류를 방지하기 위한 기본 이동 처리.
const emptyNavigation = () => {}

// 기관 유형 선택창에 표시할 기본 항목 목록 설정.
const centerTypes = [
  {
    value: 'MENTAL_HEALTH_CENTER',
    label: '정신건강복지센터',
  },
  {
    value: 'COUNSELING_CENTER',
    label: '심리상담센터',
  },
]

// 지역과 기관 유형을 선택해 검색 조건을 확인하는 화면 컴포넌트 정의.
function Center({
  isAuthenticated = false,
  isLoggingOut = false,
  onLogin = emptyNavigation,
  onLogout = emptyNavigation,
  onSignUp = emptyNavigation,
  onEmotionHistory = emptyNavigation,
  onCenter = emptyNavigation,
  onDailyCare = emptyNavigation,
  onHome = emptyNavigation,
}) {
  // 사용자가 선택한 시·도 이름 상태 관리.
  const [selectedRegion, setSelectedRegion] = useState('')
  // 사용자가 선택한 시·군·구 이름 상태 관리.
  const [selectedDistrict, setSelectedDistrict] = useState('')
  // 사용자가 선택한 읍·면·동 이름 상태 관리.
  const [selectedTown, setSelectedTown] = useState('')
  // 사용자가 선택한 기관 유형 상태 관리.
  const [selectedType, setSelectedType] = useState('')
  // 백엔드에서 받은 실제 기관 검색 결과와 페이지 정보.
  const [searchResult, setSearchResult] = useState(null)
  // 카카오 기관 검색 요청 진행 여부.
  const [isSearching, setIsSearching] = useState(false)
  // 기관 검색 실패 시 사용자에게 표시할 안내 문구.
  const [searchError, setSearchError] = useState('')
  // 선택한 시·도에 포함된 시·군·구 목록 계산.
  const districtNames = getDistrictNames(selectedRegion)
  // 선택한 시·군·구에 포함된 읍·면·동 목록 계산.
  const townNames = getTownNames(selectedRegion, selectedDistrict)
  // 모든 검색 조건이 선택되었는지 확인하는 상태 계산.
  const isSearchReady = Boolean(
    selectedRegion
    && selectedDistrict
    && selectedTown
    && selectedType,
  )
  // 선택된 기관 유형의 사용자 표시용 이름 탐색.
  const selectedTypeLabel = centerTypes.find(
    (centerType) => centerType.value === selectedType,
  )?.label

  // 검색 조건이 바뀌면 이전 조건으로 조회한 결과와 오류를 제거.
  const resetSearchResult = () => {
    setSearchResult(null)
    setSearchError('')
  }

  // 시·도 변경 시 하위 지역 선택값을 초기화하는 처리.
  const handleRegionChange = (event) => {
    setSelectedRegion(event.target.value)
    setSelectedDistrict('')
    setSelectedTown('')
    resetSearchResult()
  }

  // 시·군·구 변경 시 읍·면·동 선택값을 초기화하는 처리.
  const handleDistrictChange = (event) => {
    setSelectedDistrict(event.target.value)
    setSelectedTown('')
    resetSearchResult()
  }

  // 현재 검색 조건과 페이지 번호로 백엔드의 실제 기관 목록을 조회.
  const loadCenters = async (page = 0) => {
    if (!isSearchReady || isSearching) {
      return
    }

    setIsSearching(true)
    setSearchError('')

    try {
      const result = await searchCenters({
        region: selectedRegion,
        district: selectedDistrict,
        town: selectedTown,
        type: selectedType,
        page,
        size: 10,
      })

      setSearchResult(result)
    } catch (error) {
      setSearchResult(null)

      if (!error?.response) {
        setSearchError(
          '기관 검색 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.',
        )
      } else {
        setSearchError(
          error.response.data?.message
            || '기관 정보를 불러오지 못했습니다.',
        )
      }
    } finally {
      setIsSearching(false)
    }
  }

  // 검색 버튼을 누르면 첫 번째 페이지부터 기관을 조회.
  const handleSearch = async (event) => {
    event.preventDefault()
    await loadCenters(0)
  }

  // 공통 헤더와 검색 조건 및 실제 기관 검색 결과를 포함한 화면을 반환.
  return (
    <main className="center-page">
      {/* 공통 사이드바와 메인 이동 로고를 포함한 상단 네비게이션 배치. */}
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

      {/* 관련 기관 찾기 화면의 제목과 기본 목적 안내 배치. */}
      <section className="center-content" aria-labelledby="center-title">
        <h1 id="center-title">관련 기관 찾기</h1>
        <p>가까운 마음건강 관련 기관을 확인하는 화면입니다.</p>

        {/* 실제 기관 검색에 사용할 지역과 기관 유형 조건을 선택. */}
        <form
          className="center-filter"
          aria-label="기관 검색 조건"
          onSubmit={handleSearch}
        >
          <label htmlFor="center-region">
            <span>시·도</span>
            <select
              id="center-region"
              value={selectedRegion}
              onChange={handleRegionChange}
            >
              <option value="" disabled>
                지역 선택
              </option>
              {regionNames.map((regionName) => (
                <option key={regionName} value={regionName}>
                  {regionName}
                </option>
              ))}
            </select>
          </label>

          <label htmlFor="center-district">
            <span>시·군·구</span>
            <select
              id="center-district"
              value={selectedDistrict}
              onChange={handleDistrictChange}
              disabled={!selectedRegion}
            >
              <option value="" disabled>
                {selectedRegion ? '시·군·구 선택' : '시·도 선택 후 이용 가능'}
              </option>
              {districtNames.map((districtName) => (
                <option key={districtName} value={districtName}>
                  {districtName}
                </option>
              ))}
            </select>
          </label>

          <label htmlFor="center-town">
            <span>읍·면·동</span>
            <select
              id="center-town"
              value={selectedTown}
              onChange={(event) => {
                setSelectedTown(event.target.value)
                resetSearchResult()
              }}
              disabled={!selectedDistrict}
            >
              <option value="" disabled>
                {selectedDistrict ? '읍·면·동 선택' : '시·군·구 선택 후 이용 가능'}
              </option>
              {townNames.map((townName) => (
                <option key={townName} value={townName}>
                  {townName}
                </option>
              ))}
            </select>
          </label>

          <label htmlFor="center-type">
            <span>기관 유형</span>
            <select
              id="center-type"
              value={selectedType}
              onChange={(event) => {
                setSelectedType(event.target.value)
                resetSearchResult()
              }}
            >
              <option value="" disabled>
                유형 선택
              </option>
              {centerTypes.map((centerType) => (
                <option key={centerType.value} value={centerType.value}>
                  {centerType.label}
                </option>
              ))}
            </select>
          </label>

          {/* 모든 조건을 선택한 뒤 검색을 확정하는 버튼 배치. */}
          <div className="center-search-action">
            <p>
              {isSearchReady
                ? '선택한 조건으로 기관을 검색할 수 있습니다.'
                : '지역과 기관 유형을 모두 선택해 주세요.'}
            </p>
            <button
              type="submit"
              disabled={!isSearchReady || isSearching}
            >
              {isSearching ? '검색 중' : '기관 검색하기'}
            </button>
          </div>
        </form>

        {/* 기관 검색 실패 내용을 검색 조건 아래에 표시. */}
        {searchError && (
          <p className="center-result-error" role="alert">
            {searchError}
          </p>
        )}

        {/* 백엔드에서 실제 검색 결과를 받은 경우 기관 목록을 표시. */}
        {searchResult && (
          <section className="center-search-result" aria-live="polite">
            <div className="center-result-heading">
              <div>
                <h2>기관 검색 결과</h2>
                <p>
                  {selectedRegion} {selectedDistrict} {selectedTown}
                  {' · '}
                  {selectedTypeLabel}
                </p>
              </div>
              <strong>{searchResult.totalElements}곳</strong>
            </div>

            <p className="center-result-notice">
              카카오 장소 검색 결과이며 특정 기관을 추천하거나
              서비스 품질을 보증하는 정보는 아닙니다.
            </p>

            {(searchResult.content ?? []).length === 0 ? (
              <p className="center-empty-result">
                선택한 지역에서 해당 기관을 찾지 못했습니다.
              </p>
            ) : (
              <div className="center-result-list">
                {(searchResult.content ?? []).map((center) => (
                  <article key={center.centerId}>
                    <div className="center-result-summary">
                      <strong>{center.name}</strong>
                      <span>
                        {center.categoryName || selectedTypeLabel}
                      </span>
                    </div>

                    <address>
                      {center.roadAddress
                        || center.address
                        || '주소 정보 없음'}

                      {center.roadAddress
                        && center.address
                        && center.roadAddress !== center.address && (
                          <>
                            <br />
                            <small>지번: {center.address}</small>
                          </>
                      )}
                    </address>

                    <div className="center-result-actions">
                      {center.phone ? (
                        <a
                          className="center-phone-link"
                          href={`tel:${center.phone}`}
                        >
                          {center.phone}
                        </a>
                      ) : (
                        <span>전화번호 정보 없음</span>
                      )}

                      {center.placeUrl && (
                        <a
                          className="center-map-link"
                          href={center.placeUrl}
                          target="_blank"
                          rel="noreferrer"
                        >
                          카카오맵에서 보기
                        </a>
                      )}
                    </div>
                  </article>
                ))}
              </div>
            )}

            {searchResult.totalPages > 1 && (
              <nav
                className="center-pagination"
                aria-label="기관 검색 결과 페이지"
              >
                <button
                  type="button"
                  disabled={isSearching || searchResult.page === 0}
                  onClick={() => loadCenters(searchResult.page - 1)}
                >
                  이전
                </button>

                <span>
                  {searchResult.page + 1} / {searchResult.totalPages}
                </span>

                <button
                  type="button"
                  disabled={
                    isSearching
                    || searchResult.page + 1 >= searchResult.totalPages
                  }
                  onClick={() => loadCenters(searchResult.page + 1)}
                >
                  다음
                </button>
              </nav>
            )}
          </section>
        )}
      </section>
    </main>
  )
}

export default Center
