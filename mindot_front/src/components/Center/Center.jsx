import { useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
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
  { value: 'mental-health', label: '정신건강복지센터' },
  { value: 'counseling', label: '심리상담센터' },
]

// 실제 기관 API 연결 전 목록 화면 구성을 확인하기 위한 예시 기관 설정.
const previewCenters = [
  { id: 'preview-center-1', name: '기관명 예시 1' },
  { id: 'preview-center-2', name: '기관명 예시 2' },
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
  // 사용자가 검색 조건을 확정했는지 여부 상태 관리.
  const [hasSearched, setHasSearched] = useState(false)
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

  // 시·도 변경 시 하위 지역 선택값을 초기화하는 처리.
  const handleRegionChange = (event) => {
    setSelectedRegion(event.target.value)
    setSelectedDistrict('')
    setSelectedTown('')
    setHasSearched(false)
  }

  // 시·군·구 변경 시 읍·면·동 선택값을 초기화하는 처리.
  const handleDistrictChange = (event) => {
    setSelectedDistrict(event.target.value)
    setSelectedTown('')
    setHasSearched(false)
  }

  // 선택한 검색 조건을 확정하고 결과 안내를 표시하는 처리.
  const handleSearch = (event) => {
    event.preventDefault()

    if (!isSearchReady) {
      return
    }

    setHasSearched(true)
  }

  // 공통 헤더와 검색 조건 및 예시 결과 목록을 포함한 단계 구조 반환.
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
        onHome={onHome}
      />

      {/* 관련 기관 찾기 화면의 제목과 기본 목적 안내 배치. */}
      <section className="center-content" aria-labelledby="center-title">
        <h1 id="center-title">관련 기관 찾기</h1>
        <p>가까운 마음건강 관련 기관을 확인하는 화면입니다.</p>

        {/* 실제 검색 기능 연결 전 지역 단계와 기관 유형을 고르는 기본 조건 영역 배치. */}
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
                setHasSearched(false)
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
                setHasSearched(false)
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
            <button type="submit" disabled={!isSearchReady}>
              기관 검색하기
            </button>
          </div>
        </form>

        {/* 검색 버튼 선택 후 기관 목록의 기본 구조를 확인하는 예시 결과 배치. */}
        {hasSearched && (
          <section className="center-search-result" aria-live="polite">
            <div className="center-result-heading">
              <div>
                <h2>기관 검색 결과</h2>
                <p>
                  {selectedRegion} {selectedDistrict} {selectedTown} · {selectedTypeLabel}
                </p>
              </div>
              <strong>예시 {previewCenters.length}곳</strong>
            </div>

            <p className="center-result-notice">
              실제 기관 데이터 연결 전 목록 구성을 확인하기 위한 예시입니다.
            </p>

            {/* 선택 지역에 표시될 기관명과 상세 정보 위치를 확인하는 목록 배치. */}
            <div className="center-result-list">
              {previewCenters.map((previewCenter) => (
                <article key={previewCenter.id}>
                  <div>
                    <strong>{previewCenter.name}</strong>
                    <span>{selectedTypeLabel}</span>
                  </div>
                  <address>
                    {selectedRegion} {selectedDistrict} {selectedTown}
                    <br />
                    상세 주소·전화번호·운영 시간 연결 예정
                  </address>
                  <button type="button" disabled>
                    기관 정보 준비 중
                  </button>
                </article>
              ))}
            </div>
          </section>
        )}
      </section>
    </main>
  )
}

export default Center
