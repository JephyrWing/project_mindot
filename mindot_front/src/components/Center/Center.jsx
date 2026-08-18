import { useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Sidebar from '../Sidebar/Sidebar.jsx'
import {
  getDistrictNames,
  getTownNames,
  regionNames,
} from './regionData.js'
import './Center.css'

// 화면 연결 전 사이드바 버튼 선택 시 오류를 방지하기 위한 기본 이동 처리.
const emptyNavigation = () => {}

// 관련 기관 찾기 기능의 두 번째 개발 단계를 표시하는 기본 화면 컴포넌트 정의.
function Center({
  isAuthenticated = false,
  isLoggingOut = false,
  onLogin = emptyNavigation,
  onLogout = emptyNavigation,
  onSignUp = emptyNavigation,
  onCenter = emptyNavigation,
  onHome = emptyNavigation,
}) {
  // 사용자가 선택한 시·도 이름 상태 관리.
  const [selectedRegion, setSelectedRegion] = useState('')
  // 사용자가 선택한 시·군·구 이름 상태 관리.
  const [selectedDistrict, setSelectedDistrict] = useState('')
  // 사용자가 선택한 읍·면·동 이름 상태 관리.
  const [selectedTown, setSelectedTown] = useState('')
  // 선택한 시·도에 포함된 시·군·구 목록 계산.
  const districtNames = getDistrictNames(selectedRegion)
  // 선택한 시·군·구에 포함된 읍·면·동 목록 계산.
  const townNames = getTownNames(selectedRegion, selectedDistrict)

  // 시·도 변경 시 하위 지역 선택값을 초기화하는 처리.
  const handleRegionChange = (event) => {
    setSelectedRegion(event.target.value)
    setSelectedDistrict('')
    setSelectedTown('')
  }

  // 시·군·구 변경 시 읍·면·동 선택값을 초기화하는 처리.
  const handleDistrictChange = (event) => {
    setSelectedDistrict(event.target.value)
    setSelectedTown('')
  }

  // 공통 헤더와 간단한 검색 조건을 포함한 두 번째 단계 구조 반환.
  return (
    <main className="center-page">
      {/* 기존 공통 사이드바와 Mindot 로고를 사용하는 상단 헤더 배치. */}
      <header className="app-navigation-header center-header">
        <Sidebar
          isAuthenticated={isAuthenticated}
          isLoggingOut={isLoggingOut}
          onLogin={onLogin}
          onLogout={onLogout}
          onSignUp={onSignUp}
          onCenter={onCenter}
          onHome={onHome}
        />
        <BrandLogo className="app-navigation-brand" onClick={onHome} />
      </header>

      {/* 관련 기관 찾기 화면의 제목과 기본 목적 안내 배치. */}
      <section className="center-content" aria-labelledby="center-title">
        <h1 id="center-title">관련 기관 찾기</h1>
        <p>가까운 마음건강 관련 기관을 확인하는 화면입니다.</p>

        {/* 실제 검색 기능 연결 전 지역 단계와 기관 유형을 고르는 기본 조건 영역 배치. */}
        <div className="center-filter" aria-label="기관 검색 조건">
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
              onChange={(event) => setSelectedTown(event.target.value)}
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
            <select id="center-type" defaultValue="">
              <option value="" disabled>
                유형 선택
              </option>
              <option value="mental-health">정신건강복지센터</option>
              <option value="counseling">심리상담센터</option>
            </select>
          </label>
        </div>
      </section>
    </main>
  )
}

export default Center
