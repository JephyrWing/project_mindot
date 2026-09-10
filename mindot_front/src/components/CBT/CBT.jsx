import { automaticThoughtError, MAX_AUTOMATIC_THOUGHT_LENGTH } from '../../utils/reflections/automaticThought.js'
import { useEffect, useRef, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import InsightResult from './InsightResult.jsx'
import { newRequestKey, openReflection, submitReflectionAnswer, retryReflection, confirmReflection,
  cancelReflection, getReflectionSessionDetail, retryReflectionEmbedding } from '../../utils/reflections/reflectionsApi.js'
import { confirmEmotionRecord, getEmotionRecordDetail } from '../../utils/records/recordsApi.js'
import { acceptSessionView } from '../../utils/reflections/sessionView.js'
import { confirmThoughtForOpen } from '../../utils/reflections/confirmThoughtForOpen.js'
import './CBT.css'

const scoreFields = [
  ['beforeBeliefStrength', '처음 자동적 생각을 성찰 전에 믿었던 정도', 100],
  ['afterBeliefStrength', '같은 처음 자동적 생각을 지금 믿는 정도', 100],
  ['finalEmotionIntensity', '지금 감정의 강도', 10], ['helpfulnessScore', '성찰이 도움이 된 정도', 5],
]
const pending = (v) => ['PROCESSING', 'PENDING'].includes(v?.job?.status)
const errorMessage = (e) => e.userMessage || (typeof e.response?.data?.detail === 'string' ? e.response.data.detail
  : e.response?.data?.message || '요청 결과를 확인하지 못했습니다. 작성한 내용을 유지하고 다시 확인해 주세요.'
)

export default function CBT(props) {
  const { emotionRecordId, resumeSession, resumeSessionId, onSessionStarted, onEmotionHistory, onHome } = props
  const [view, setView] = useState(null)
  const current = useRef(null)
  const requests = useRef(new Map())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [answer, setAnswer] = useState('')
  const [record, setRecord] = useState(null)
  const [confirmedRecord, setConfirmedRecord] = useState(null)
  const [thought, setThought] = useState('')
  const [reviews, setReviews] = useState({})
  const [scores, setScores] = useState({})
  const [reload, setReload] = useState(0)
  const [embeddingMessage, setEmbeddingMessage] = useState('')
  const apply = (next) => {
    if (!acceptSessionView(current.current, next)) return
    current.current = next
    setView(next)
  }
  const send = async (kind, body, method, revision = current.current?.revision) => {
    const signature = `${kind}:${JSON.stringify(body)}`
    let receipt = requests.current.get(signature)
    if (!receipt) {
      receipt = { key: newRequestKey(), revision }
      requests.current.set(signature, receipt)
    }
    try {
      const result = await method(receipt.key, receipt.revision)
      requests.current.delete(signature)
      apply(result)
      return result
    } catch (e) {
      // A transport retry keeps the same body/key/revision; no new logical answer.
      if (kind !== 'OPEN' && e.response && e.response.status < 500) requests.current.delete(signature)
      throw e
    }
  }
  const resumeId = resumeSessionId ?? resumeSession?.sessionId
  useEffect(() => {
    if (!resumeId || current.current?.sessionId === resumeId) return
    let active = true
    const load = async () => {
      setBusy(true); setError('')
      try {
        const saved = await getReflectionSessionDetail(resumeId)
        if (!active) return
        apply(saved)
        const hydrated = await openReflection({ sessionId: resumeId }, newRequestKey(), saved.revision)
        if (active) apply(hydrated)
      } catch (e) { if (active) setError(errorMessage(e)) }
      finally { if (active) setBusy(false) }
    }
    load()
    return () => { active = false }
  }, [resumeId, reload])
  useEffect(() => {
    if (!pending(view)) return
    let active = true
    const timer = setInterval(async () => {
      try { const next = await getReflectionSessionDetail(view.sessionId); if (active) apply(next) }
      catch { /* Keep saved view; manual refresh remains available. */ }
    }, 2000)
    return () => { active = false; clearInterval(timer) }
  }, [view?.sessionId, view?.job?.status])
  const proposalId = view?.currentProposal?.proposalId
  useEffect(() => { setReviews({}); setScores({}) }, [proposalId])
  const run = async (action) => {
    setBusy(true); setError('')
    try { await action() }
    catch (e) {
      setError(errorMessage(e))
      if (current.current?.sessionId) {
        try { apply(await getReflectionSessionDetail(current.current.sessionId)) } catch { /* Preserve input. */ }
      }
    } finally { setBusy(false) }
  }
  const openSavedRecord = async () => {
    const result = await send('OPEN', { emotionRecordId }, (key) => openReflection({ emotionRecordId }, key))
    onSessionStarted?.(result.sessionId)
  }
  const start = () => run(async () => {
    if (!emotionRecordId) return
    if (confirmedRecord) { await openSavedRecord(); return }
    const saved = await getEmotionRecordDetail(emotionRecordId)
    if (!saved.automaticThought?.trim()) { setRecord(saved); return }
    const invalid = automaticThoughtError(saved.automaticThought)
    if (invalid) { setError(invalid); return }
    await openSavedRecord()
  })
  const saveThought = (e) => {
    e.preventDefault()
    const invalid = automaticThoughtError(thought)
    if (invalid) { setError(invalid); return }
    run(async () => {
      const saved = await confirmThoughtForOpen(emotionRecordId, {
        situationText: record.situationText, automaticThought: thought.trim(),
        primaryEmotionCode: record.primaryEmotionCode, primaryIntensity: record.primaryIntensity,
        secondaryEmotions: record.secondaryEmotions ?? [], contextCategory: record.contextCategory,
        relatedPersonType: record.relatedPersonType, details: record.details ?? {},
      }, { confirmEmotionRecord, getEmotionRecordDetail })
      setConfirmedRecord(saved)
      setRecord(null) // This durable stage is complete even if OPEN loses its response.
      await openSavedRecord()
    })
  }
  const submit = (e) => {
    e.preventDefault()
    const text = answer
    if (!text.trim() || busy || pending(view)) return
    run(async () => {
      await send('TURN', { answer: text }, (key, rev) => submitReflectionAnswer(view.sessionId, text, key, rev))
      setAnswer('')
    })
  }
  const confirm = (e) => {
    e.preventDefault()
    const proposal = view.currentProposal
    const body = { proposalId: proposal.proposalId,
      reviews: proposal.suggestions.map((s) => ({ code: s.code, reviewStatus: reviews[s.code] })),
      ...Object.fromEntries(scoreFields.map(([key]) => [key, Number(scores[key])])),
    }
    run(() => send('CONFIRM', body, (key, rev) => confirmReflection(view.sessionId, body, key, rev)))
  }
  const cancel = () => {
    if (!window.confirm('이 성찰을 완전히 중단할까요? 문답은 보존되지만 이 세션을 이어갈 수 없습니다.')) return
    run(async () => {
      apply(await getReflectionSessionDetail(view.sessionId))
      await send('CANCEL', {}, (key, rev) => cancelReflection(view.sessionId, key, rev))
    })
  }
  const refresh = () => run(async () => {
    if (current.current) apply(await getReflectionSessionDetail(current.current.sessionId))
    else setReload((n) => n + 1)
  })
  const open = view?.status === 'OPEN'
  const failed = view?.job?.retryable
  const disabled = busy || pending(view)
  return <main className="cbt-page">
    <Navbar {...props} />
    <div className="cbt-content"><section className="cbt-card">
      <BrandLogo className="cbt-logo" onClick={onHome} />
      <h1>CBT 성찰</h1>
      {error && <div role="alert"><p>{error}</p><button type="button" onClick={refresh}>현재 결과 확인</button></div>}
      {!view ? <>
        <p>감정이 생긴 순간의 생각을 편안한 속도로 살펴보세요.</p>
        {confirmedRecord && <p>생각은 저장됐습니다. 성찰 시작을 다시 시도할 수 있어요.</p>}
        {record ? <form className="cbt-automatic-thought-form" onSubmit={saveThought}>
          <label>그때 처음 떠오른 생각<textarea required maxLength={MAX_AUTOMATIC_THOUGHT_LENGTH} value={thought} onChange={(e) => setThought(e.target.value)} /></label>
          <button disabled={busy || !thought.trim()}>저장하고 시작하기</button>
        </form> : <button className="cbt-start-button" disabled={!emotionRecordId || busy} onClick={start}>{busy ? '불러오는 중…' : '성찰 시작하기'}</button>}
        {!emotionRecordId && !resumeId && <p>감정 기록을 선택한 뒤 시작해 주세요.</p>}
      </> : <section className="cbt-chat">
        <p>처음 생각: {view.record.automaticThought}</p>
        <div className="cbt-chat-messages" role="log" aria-live="polite">
          {view.messages.map((m) => <div key={m.messageNumber} className={`cbt-message cbt-message--${m.role === 'USER' ? 'user' : 'ai'}`}>
            <strong>{m.role === 'USER' ? '나' : 'Mindot AI'}</strong><p style={{ whiteSpace: 'pre-wrap' }}>{m.content}</p>
          </div>)}
        </div>
        {pending(view) && <p role="status">답변은 저장됐습니다. 응답을 준비하고 있어요.</p>}
        {failed && <div role="alert"><p>생성을 마치지 못했습니다. 저장된 입력으로 다시 시도할 수 있어요.</p>
          <button disabled={busy} onClick={() => run(() => send('RETRY', {}, (key, rev) => retryReflection(view.sessionId, key, rev)))}>생성 다시 시도</button></div>}
        {open && view.phase === 'PROPOSAL_REVIEW' && view.currentProposal && !disabled && <form className="cbt-confirm-form" onSubmit={confirm}>
          <InsightResult result={view.currentProposal} reviews={reviews} onReview={(code, value) => setReviews((r) => ({ ...r, [code]: value }))} />
          <p>수정한 생각이 자신의 뜻과 다르면 아래 답변으로 정정해 주세요. 유형을 모두 거부해도 저장할 수 있습니다.</p>
          <div className="cbt-confirm-scores">{scoreFields.map(([key, label, max]) => <label key={key}>{label} (0–{max})
            <input required type="number" min="0" max={max} step="1" value={scores[key] ?? ''} onChange={(e) => setScores((s) => ({ ...s, [key]: e.target.value }))} />
          </label>)}</div>
          <button type="submit">이 생각과 유형 검토를 확인하고 저장</button>
        </form>}
        {open && !failed && <form className="cbt-chat-form" onSubmit={submit}>
          <label htmlFor="cbt-answer">{view.currentProposal ? '제안의 뜻 정정 또는 설명 요청' : '답변'}</label>
          <textarea id="cbt-answer" maxLength={10000} value={answer} disabled={disabled} onChange={(e) => setAnswer(e.target.value)} />
          <button disabled={disabled || !answer.trim()}>보내기</button>
        </form>}
        {view.confirmedResult && <><h2>확인한 성찰 결과</h2><InsightResult result={view.confirmedResult} />
          <button onClick={() => run(async () => { await retryReflectionEmbedding(view.sessionId); setEmbeddingMessage('검색 연결을 완료했습니다.') })}>검색 연결 다시 시도</button>
          {embeddingMessage && <p role="status">{embeddingMessage}</p>}
        </>}
        {!open && <p>{view.status === 'COMPLETED' ? '성찰 결과가 저장됐습니다.' : view.status === 'CANCELLED' ? '성찰을 완전히 중단했습니다. 문답은 보존됩니다.' : '안전을 위해 성찰을 중단했습니다.'}</p>}
        <div className="cbt-session-actions"><div>
          <button className="cbt-later-button" onClick={onEmotionHistory}>{open ? '나중에 이어하기' : '기록 목록으로'}</button>
          {open && <button className="cbt-cancel-button" onClick={cancel}>성찰 완전히 중단</button>}
        </div></div>
      </section>}
    </section></div>
  </main>
}
