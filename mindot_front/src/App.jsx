// 로그인 화면 전체에서 사용하는 공통 스타일을 불러온다.
import './App.css'
// 현재 프로젝트에 남겨 둔 로그인 화면 컴포넌트를 불러온다.
import Login from './components/Login/Login.jsx'

// 애플리케이션의 최상위 화면을 구성하는 루트 컴포넌트다.
function App() {
  // 순차 개발을 위해 현재 단계에서는 로그인 화면만 렌더링한다.
  return <Login />
}

// 다른 진입 파일에서 App 컴포넌트를 불러올 수 있도록 기본 내보내기를 설정한다.
export default App
