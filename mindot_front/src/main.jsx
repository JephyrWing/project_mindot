import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'
import { registerServiceWorker } from './utils/pwa/registerServiceWorker.js'

// 지원 브라우저에서 PWA 기반이 되는 기본 서비스 워커 등록.
registerServiceWorker()

// 리액트 애플리케이션의 최상위 App 컴포넌트 렌더링.
createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
