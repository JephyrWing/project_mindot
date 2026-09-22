import { useEffect } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import './ServiceInfo.css'

const policyPages = [
  { page: 'about', path: '/about', label: '서비스 소개' },
  { page: 'terms', path: '/terms', label: '이용약관' },
  { page: 'privacy', path: '/privacy', label: '개인정보 처리방침' },
  { page: 'research', path: '/about/research', label: '연구 근거·AI 한계' },
]

const pageMeta = {
  about: {
    eyebrow: '서비스 안내',
    title: 'MINDOT 소개',
    description: '감정과 생각을 기록하고 돌아보는 과정을 돕는 자기이해 보조 서비스입니다.',
  },
  terms: {
    eyebrow: 'terms-v1',
    title: '이용약관',
    description: 'MINDOT을 안전하게 이용하기 위해 필요한 기본 원칙을 안내합니다.',
  },
  privacy: {
    eyebrow: 'privacy-v1',
    title: '개인정보 처리방침',
    description: '서비스에서 처리하는 정보와 보유 기간, 삭제 방법을 안내합니다.',
  },
  research: {
    eyebrow: '근거와 한계',
    title: '연구 근거·AI 해석 한계',
    description: 'MINDOT이 참고한 자기기록·CBT 개념과 AI 결과를 해석할 때의 한계를 설명합니다.',
  },
}

function AboutContent() {
  return (
    <>
      <section className="service-info-section" aria-labelledby="about-purpose-title">
        <h2 id="about-purpose-title">기록을 통한 자기이해</h2>
        <p>
          MINDOT은 그날의 상황, 감정과 떠오른 생각을 짧게 남기고 시간이 지난 뒤
          반복되는 흐름을 스스로 확인할 수 있도록 돕습니다. 기록은 사용자의 경험을
          대신 판단하기 위한 자료가 아니라, 사용자가 자신의 마음을 돌아보기 위한 자료입니다.
        </p>
      </section>

      <section className="service-info-section" aria-labelledby="about-flow-title">
        <h2 id="about-flow-title">이용 흐름</h2>
        <ol className="service-info-steps">
          <li><strong>기록하기</strong><span>지금의 감정과 생각을 짧게 남깁니다.</span></li>
          <li><strong>확인하기</strong><span>AI가 정리한 제안을 직접 수정하고 확정합니다.</span></li>
          <li><strong>성찰하기</strong><span>질문에 답하며 다른 관점과 생각의 변화를 살펴봅니다.</span></li>
          <li><strong>돌아보기</strong><span>주간·월간 리포트와 반복 패턴으로 기록의 흐름을 확인합니다.</span></li>
        </ol>
      </section>

      <section className="service-info-section service-info-callout" aria-labelledby="about-boundary-title">
        <h2 id="about-boundary-title">서비스가 하지 않는 일</h2>
        <p>
          MINDOT은 의료적 진단, 치료, 처방 또는 전문 상담을 제공하지 않습니다.
          AI 결과는 참고용 제안이며 정확하거나 완전하다고 보장할 수 없습니다.
          위기 상황에서는 앱보다 112·119·109 또는 가까운 전문기관의 도움을 먼저 이용해 주세요.
        </p>
      </section>
    </>
  )
}

function TermsContent() {
  return (
    <>
      <p className="service-info-effective-date">시행일: 2026년 9월 21일</p>

      <section className="service-info-section" aria-labelledby="terms-purpose-title">
        <h2 id="terms-purpose-title">1. 목적과 적용</h2>
        <p>
          이 약관은 MINDOT의 감정 기록, AI 구조화, CBT 성찰, 리포트와 관련 기능을
          이용할 때 서비스와 사용자 사이에 적용되는 기본 조건을 정합니다.
        </p>
      </section>

      <section className="service-info-section" aria-labelledby="terms-account-title">
        <h2 id="terms-account-title">2. 계정 이용</h2>
        <ul>
          <li>사용자는 본인의 정확한 정보로 계정을 만들고 로그인 정보를 안전하게 관리해야 합니다.</li>
          <li>다른 사람의 계정을 사용하거나 서비스의 정상 동작을 방해해서는 안 됩니다.</li>
          <li>계정에서 발생한 이상 활동을 확인하면 비밀번호를 변경하고 서비스 운영자에게 알려야 합니다.</li>
        </ul>
      </section>

      <section className="service-info-section" aria-labelledby="terms-content-title">
        <h2 id="terms-content-title">3. 기록과 AI 결과</h2>
        <ul>
          <li>사용자가 작성한 기록은 기능 제공을 위해 저장·분석될 수 있습니다.</li>
          <li>AI가 제안한 감정, 생각과 패턴은 오류·누락·편향을 포함할 수 있으며 사용자가 직접 확인해야 합니다.</li>
          <li>AI 결과를 의료적 판단, 긴급 대응 또는 중요한 의사결정의 유일한 근거로 사용해서는 안 됩니다.</li>
        </ul>
      </section>

      <section className="service-info-section" aria-labelledby="terms-safety-title">
        <h2 id="terms-safety-title">4. 안전한 이용</h2>
        <p>
          사용자는 불법적인 목적, 타인의 권리 침해, 시스템 공격·우회 또는 서비스 악용을 위해
          MINDOT을 이용할 수 없습니다. 즉각적인 위험이나 자해·타해 가능성이 있는 경우에는
          앱의 응답을 기다리지 말고 112·119·109 또는 주변 사람과 전문기관에 도움을 요청해야 합니다.
        </p>
      </section>

      <section className="service-info-section" aria-labelledby="terms-operation-title">
        <h2 id="terms-operation-title">5. 서비스 변경과 중단</h2>
        <p>
          점검, 네트워크 장애, 외부 AI·음성 인식 서비스 장애 또는 보안상 필요한 경우
          일부 기능이 제한될 수 있습니다. 중요한 기능이나 약관이 바뀌는 경우 적용 전에
          서비스 화면을 통해 변경 내용을 안내합니다.
        </p>
      </section>

      <section className="service-info-section" aria-labelledby="terms-withdrawal-title">
        <h2 id="terms-withdrawal-title">6. 동의 철회와 회원 탈퇴</h2>
        <p>
          AI 분석 동의는 설정에서 철회할 수 있으며, 이후 새로운 AI 분석과 CBT 기능이 제한됩니다.
          이용약관 및 개인정보 처리 동의는 서비스 제공에 필수이므로 회원 탈퇴를 통해 철회할 수 있습니다.
          회원 탈퇴가 완료되면 계정과 관련 기록은 복구할 수 없습니다.
        </p>
      </section>
    </>
  )
}

function PrivacyContent() {
  return (
    <>
      <p className="service-info-effective-date">시행일: 2026년 9월 21일</p>

      <section className="service-info-section" aria-labelledby="privacy-principle-title">
        <h2 id="privacy-principle-title">처리 원칙</h2>
        <p>
          MINDOT은 회원 인증과 사용자가 선택한 기록·분석 기능을 제공하는 데 필요한 정보만 처리합니다.
          개인정보가 더 이상 필요하지 않으면 지체 없이 삭제하는 것을 원칙으로 합니다.
        </p>
      </section>

      <section className="service-info-section" aria-labelledby="privacy-table-title">
        <h2 id="privacy-table-title">처리 항목·목적·보유 기간</h2>
        <div className="service-info-table-wrap">
          <table>
            <thead>
              <tr><th scope="col">구분</th><th scope="col">처리 항목과 목적</th><th scope="col">보유 기간</th></tr>
            </thead>
            <tbody>
              <tr>
                <th scope="row">계정</th>
                <td>이메일, 닉네임, 비밀번호 해시 또는 소셜 계정 식별자, 언어·시간대, 계정 상태</td>
                <td>회원 탈퇴 시까지</td>
              </tr>
              <tr>
                <th scope="row">마음 기록</th>
                <td>감정 원문, 발생 시각, 감정·상황·생각, 사용자 확정값, 검색용 연결 정보</td>
                <td>개별 기록 삭제 또는 회원 탈퇴 시까지</td>
              </tr>
              <tr>
                <th scope="row">CBT·리포트</th>
                <td>질문과 답변, 인지왜곡 제안·검토, 전후 점수, 리포트와 근거 기록</td>
                <td>연결 기록 삭제 또는 회원 탈퇴 시까지</td>
              </tr>
              <tr>
                <th scope="row">동의·설정</th>
                <td>동의 종류·버전·변경 이력, 알림 설정</td>
                <td>회원 탈퇴 시까지</td>
              </tr>
              <tr>
                <th scope="row">로그인 세션</th>
                <td>Refresh 세션 정보</td>
                <td>발급 후 14일 또는 로그아웃·회원 탈퇴 시까지</td>
              </tr>
              <tr>
                <th scope="row">음성 입력</th>
                <td>음성을 문장으로 변환하기 위한 요청 데이터</td>
                <td>변환 처리 후 원본 음성은 저장하지 않음</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section className="service-info-section" aria-labelledby="privacy-external-title">
        <h2 id="privacy-external-title">외부 처리 서비스</h2>
        <ul>
          <li>AI 구조화·성찰·패턴 설명에는 필요한 기록 문맥이 OpenAI API 처리 환경으로 전달될 수 있습니다.</li>
          <li>음성 입력을 선택하면 원본 음성이 Google Cloud Speech-to-Text 처리 환경으로 전달됩니다.</li>
          <li>정식 운영 전 운영 주체는 계약·서버 위치·외부 사업자의 보유 설정을 확정하고 이 방침에 반영해야 합니다.</li>
        </ul>
      </section>

      <section className="service-info-section" aria-labelledby="privacy-delete-title">
        <h2 id="privacy-delete-title">삭제와 파기</h2>
        <ul>
          <li>감정 기록을 삭제하면 해당 기록과 연결된 CBT 성찰 데이터도 함께 삭제됩니다.</li>
          <li>회원 탈퇴가 성공하면 운영 데이터베이스에서 계정과 사용자 소유 기록을 삭제하고 로그인 세션을 폐기합니다.</li>
          <li>전자적 기록은 데이터베이스 삭제 또는 저장 매체에서 복구하기 어려운 방식으로 파기합니다.</li>
          <li>법령상 별도 보존 의무가 생기는 정보는 다른 정보와 분리하여 해당 기간만 보관한 뒤 파기합니다.</li>
        </ul>
        <p className="service-info-note">
          현재 저장소에는 운영 백업의 보존 주기와 개인정보 문의 담당자가 정해져 있지 않습니다.
          실제 공개 서비스 전 해당 항목을 확정해 이 방침을 갱신해야 합니다.
        </p>
      </section>

      <section className="service-info-section" aria-labelledby="privacy-rights-title">
        <h2 id="privacy-rights-title">사용자의 선택과 권리</h2>
        <p>
          사용자는 기록 상세에서 내용을 확인·수정·삭제할 수 있고, 설정에서 AI 분석 동의를 철회하거나
          계정을 탈퇴할 수 있습니다. 이용약관과 개인정보 처리 동의는 회원 탈퇴 없이 개별 철회할 수 없습니다.
        </p>
      </section>
    </>
  )
}

function ResearchContent() {
  return (
    <>
      <section className="service-info-section service-info-callout" aria-labelledby="research-boundary-title">
        <h2 id="research-boundary-title">먼저 알아두세요</h2>
        <p>
          아래 자료는 MINDOT이 사용하는 자기기록과 CBT 개념을 이해하기 위한 참고 근거입니다.
          이 자료가 MINDOT 자체의 임상적 효과나 진단 정확성을 입증하는 것은 아닙니다.
          MINDOT은 의료적 진단·치료·전문 상담을 대신하지 않습니다.
        </p>
      </section>

      <section className="service-info-section" aria-labelledby="research-cbt-title">
        <h2 id="research-cbt-title">자기기록과 CBT 개념</h2>
        <p>
          CBT는 생각, 감정과 행동의 관계를 살피고 도움이 되지 않는 자동적 사고를 알아차려
          다른 관점을 검토하는 구조를 사용합니다. MINDOT의 기록과 질문 흐름은 이 개념을
          자기이해용 인터페이스에 제한적으로 적용합니다.
        </p>
        <div className="service-info-sources" aria-label="CBT 참고 자료">
          <a href="https://www.nimh.nih.gov/health/topics/psychotherapies" target="_blank" rel="noreferrer">
            <strong>미국 국립정신건강연구소(NIMH)</strong>
            <span>Psychotherapies</span>
          </a>
          <a href="https://www.nice.org.uk/guidance/ng222/chapter/Recommendations" target="_blank" rel="noreferrer">
            <strong>영국 NICE</strong>
            <span>Depression in adults: recommendations</span>
          </a>
        </div>
      </section>

      <section className="service-info-section" aria-labelledby="research-ai-title">
        <h2 id="research-ai-title">AI 해석의 한계</h2>
        <ul>
          <li>AI는 입력이 짧거나 모호할 때 실제 경험과 다른 감정·상황·생각을 제안할 수 있습니다.</li>
          <li>학습 데이터와 모델 특성에 따른 편향, 맥락 누락, 일관되지 않은 답변이 생길 수 있습니다.</li>
          <li>위험 표현을 놓치거나 반대로 위험하지 않은 내용을 위험 신호로 판단할 수 있습니다.</li>
          <li>사용자는 AI 제안을 직접 수정·확정해야 하며, 중요한 판단은 전문가와 상의해야 합니다.</li>
        </ul>
        <div className="service-info-sources" aria-label="건강 AI 참고 자료">
          <a href="https://www.who.int/publications/i/item/9789240037403" target="_blank" rel="noreferrer">
            <strong>세계보건기구(WHO)</strong>
            <span>Ethics and governance of AI for health</span>
          </a>
          <a href="https://www.who.int/news/item/16-05-2023-who-calls-for-safe-and-ethical-ai-for-health" target="_blank" rel="noreferrer">
            <strong>세계보건기구(WHO)</strong>
            <span>Safe and ethical AI for health</span>
          </a>
        </div>
      </section>

      <section className="service-info-section" aria-labelledby="research-use-title">
        <h2 id="research-use-title">안전하게 사용하는 방법</h2>
        <ol>
          <li>AI 결과를 사실이나 진단으로 받아들이지 말고 내 경험과 맞는지 확인합니다.</li>
          <li>불편하거나 틀린 제안은 수정하거나 거절합니다.</li>
          <li>감정적 어려움이 지속되거나 일상 기능에 영향을 주면 정신건강 전문가와 상의합니다.</li>
          <li>즉각적인 위험이 있으면 112·119·109 또는 가까운 응급·상담기관에 연락합니다.</li>
        </ol>
      </section>
    </>
  )
}

const pageContents = {
  about: <AboutContent />,
  terms: <TermsContent />,
  privacy: <PrivacyContent />,
  research: <ResearchContent />,
}

function ServiceInfo({ pageType = 'about', onNavigate, ...navbarProps }) {
  const activePage = pageMeta[pageType] ? pageType : 'about'
  const meta = pageMeta[activePage]

  useEffect(() => {
    const previousTitle = document.title
    document.title = `${meta.title} | MINDOT`

    return () => {
      document.title = previousTitle
    }
  }, [meta.title])

  const handleNavigation = (event, page) => {
    if (!onNavigate) return
    event.preventDefault()
    onNavigate(page)
  }

  return (
    <main className="service-info-page">
      <Navbar {...navbarProps} />
      <div className="service-info-shell">
        <nav className="service-info-navigation" aria-label="서비스·정책 안내">
          {policyPages.map((item) => (
            <a
              href={item.path}
              aria-current={activePage === item.page ? 'page' : undefined}
              key={item.page}
              onClick={(event) => handleNavigation(event, item.page)}
            >
              {item.label}
            </a>
          ))}
        </nav>

        <article className="service-info-card" aria-labelledby="service-info-title">
          <header className="service-info-heading">
            <span>{meta.eyebrow}</span>
            <h1 id="service-info-title">{meta.title}</h1>
            <p>{meta.description}</p>
          </header>
          <div className="service-info-content">{pageContents[activePage]}</div>
        </article>
      </div>
    </main>
  )
}

export default ServiceInfo
