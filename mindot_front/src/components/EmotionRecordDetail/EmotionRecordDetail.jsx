import { useEffect, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import {
  confirmEmotionRecord,
  deleteEmotionRecord,
  getEmotionRecordDetail,
  getEmotionRecordPatternExplanation,
  reanalyzeEmotionRecord,
  updateEmotionRecordOccurredAt,
} from '../../utils/records/recordsApi.js'
import './EmotionRecordDetail.css'

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

// 기록 시간대 코드를 사용자에게 표시할 한국어 이름으로 변환하기 위한 목록 설정.
const timeBucketLabels = {
  DAWN: '새벽',
  MORNING: '아침',
  AFTERNOON: '오후',
  EVENING: '저녁',
  NIGHT: '밤',
}

// 기록 완성 상태를 사용자에게 표시할 한국어 이름으로 변환하기 위한 목록 설정.
const completionStatusLabels = {
  QUICK: '간편 기록',
  PARTIAL: '분석 확인 필요',
  COMPLETE: '기록 완료',
}

// 관계 유형 코드를 사용자에게 표시할 한국어 이름으로 변환하기 위한 목록 설정.
const relatedPersonTypeLabels = {
  COLLEAGUE: '직장 동료',
  FRIEND: '친구',
  FAMILY: '가족',
  OTHER: '기타',
}

// 패턴 설명에서 반환된 인지왜곡 코드를 한국어 이름으로 변환하기 위한 목록 설정.
const distortionCodeLabels = {
  ALL_OR_NOTHING_THINKING: '흑백논리',
  CATASTROPHIZING_FORTUNE_TELLING: '파국화·미래예측',
  DISQUALIFYING_DISCOUNTING_POSITIVE: '긍정적인 면 무시',
  EMOTIONAL_REASONING: '감정적 추론',
  LABELING: '낙인찍기',
  MAGNIFICATION_MINIMIZATION: '과장·축소',
  MENTAL_FILTER_SELECTIVE_ABSTRACTION: '정신적 여과',
  MIND_READING: '독심술',
  OVERGENERALIZATION: '과잉일반화',
  PERSONALIZATION: '개인화',
  SHOULD_MUST_STATEMENTS: '당위적 사고',
  TUNNEL_VISION: '터널 시야',
}

// 상세 응답을 사용자가 수정할 수 있는 분석 확인 입력값으로 변환.
const createAnalysisForm = (record) => ({
  situationText: record?.situationText ?? '',
  automaticThought: record?.automaticThought ?? '',
  primaryEmotionCode: record?.primaryEmotionCode ?? '',
  primaryIntensity: record?.primaryIntensity ?? '',
  secondaryEmotions: (record?.secondaryEmotions ?? []).map((emotion) => ({
    code: emotion.code ?? emotion.emotionCode ?? emotion.name ?? '',
    intensity: emotion.intensity ?? emotion.score ?? '',
  })),
  contextCategory: record?.contextCategory ?? '',
  relatedPersonType: record?.relatedPersonType ?? '',
  interpretation: record?.details?.interpretation ?? '',
  bodyReaction: record?.details?.bodyReaction ?? '',
  behavior: record?.details?.behavior ?? '',
})

// 비어 있는 상세 항목에 공통으로 표시할 안내 문구 반환.
const getDisplayValue = (value, fallback = '분석 전') => (
  value === null || value === undefined || value === '' ? fallback : value
)

// 감정 기록 시각을 사용자가 읽기 쉬운 한국어 형식으로 변환하는 함수 정의.
const formatDetailDate = (occurredAt) => new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  weekday: 'short',
  hour: '2-digit',
  minute: '2-digit',
}).format(new Date(occurredAt))

// API의 UTC 시각을 사용자의 현재 지역 기준 날짜 및 시간 입력값으로 변환.
const toDateTimeLocalValue = (occurredAt) => {
  const occurredDate = new Date(occurredAt)

  if (Number.isNaN(occurredDate.getTime())) return ''

  const timezoneOffset = occurredDate.getTimezoneOffset() * 60 * 1000

  return new Date(occurredDate.getTime() - timezoneOffset)
    .toISOString()
    .slice(0, 16)
}

// 미래 시각 선택 방지를 위한 현재 지역 기준 날짜 및 시간 최댓값 생성.
const getCurrentDateTimeLocalValue = () => toDateTimeLocalValue(new Date())

// 감정 기록 상세 API 오류 상태에 따른 사용자 안내 문구 반환.
const getDetailErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (error.response.status === 404) {
    return '선택한 감정 기록을 찾을 수 없습니다.'
  }

  return '감정 기록 상세 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 감정 발생 시각 수정 API 오류 상태에 따른 사용자 안내 문구 반환.
const getUpdateErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 400) {
    return '선택한 날짜와 시간을 확인해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (error.response.status === 404) {
    return '수정할 감정 기록을 찾을 수 없습니다.'
  }

  return '감정 발생 시각을 수정하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 감정 기록 삭제 API 오류 상태에 따른 사용자 안내 문구 반환.
const getDeleteErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (error.response.status === 404) {
    return '삭제할 감정 기록을 찾을 수 없습니다.'
  }

  return '감정 기록을 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 분석 결과 확정 API 오류 상태에 따른 사용자 안내 문구 반환.
const getConfirmErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 400) {
    return '입력한 분석 결과를 확인해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (error.response.status === 404) {
    return '확정할 감정 기록을 찾을 수 없습니다.'
  }
  if (error.response.status === 409) {
    return '현재 상태에서는 분석 결과를 확정할 수 없습니다.'
  }

  return '분석 결과를 확정하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 감정 기록 재분석 API 오류 상태에 따른 사용자 안내 문구 반환.
const getReanalysisErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (error.response.status === 404) {
    return '재분석할 감정 기록을 찾을 수 없습니다.'
  }
  if (error.response.status === 409) {
    return '현재 상태에서는 재분석을 요청할 수 없습니다.'
  }
  if (error.response.status === 502 || error.response.status === 503) {
    return 'AI 분석 서버가 일시적으로 응답하지 않습니다. 잠시 후 다시 분석해 주세요.'
  }

  return 'AI 재분석에 실패했습니다. 잠시 후 다시 시도해 주세요.'
}

// 패턴 설명 API 오류 상태에 따른 사용자 안내 문구 반환.
const getPatternErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (error.response.status === 404) {
    return '패턴을 확인할 감정 기록을 찾을 수 없습니다.'
  }
  if (error.response.status === 409) {
    return '패턴 설명에 필요한 완료된 CBT 기록이 아직 충분하지 않습니다.'
  }

  return '패턴 설명을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 선택한 감정 기록 한 건을 API로 조회하고 상세 정보를 제공하는 화면 정의.
function EmotionRecordDetail({
  emotionRecordId,
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onDailyCare,
  onBack,
  onHome,
}) {
  // 백엔드에서 조회한 감정 기록 상세 정보 상태 설정.
  const [record, setRecord] = useState(null)
  // 감정 기록 상세 API 요청 진행 여부 상태 설정.
  const [isLoading, setIsLoading] = useState(true)
  // 감정 기록 상세 API 요청 실패 안내 문구 상태 설정.
  const [loadError, setLoadError] = useState('')
  // 사용자가 상세 재조회 버튼을 선택한 횟수 상태 설정.
  const [reloadCount, setReloadCount] = useState(0)
  // 사용자가 수정할 감정 발생 날짜와 시간 입력값 상태 설정.
  const [occurredAtInput, setOccurredAtInput] = useState('')
  // 감정 발생 시각 수정 API 요청 진행 여부 상태 설정.
  const [isUpdatingOccurredAt, setIsUpdatingOccurredAt] = useState(false)
  // 감정 발생 시각 수정 결과 안내 문구 상태 설정.
  const [occurredAtMessage, setOccurredAtMessage] = useState('')
  // 감정 발생 시각 수정 실패 여부 상태 설정.
  const [isOccurredAtError, setIsOccurredAtError] = useState(false)
  // 사용자의 감정 기록 삭제 확인 영역 표시 여부 상태 설정.
  const [isDeleteConfirmOpen, setIsDeleteConfirmOpen] = useState(false)
  // 감정 기록 삭제 API 요청 진행 여부 상태 설정.
  const [isDeleting, setIsDeleting] = useState(false)
  // 감정 기록 삭제 API 요청 실패 안내 문구 상태 설정.
  const [deleteError, setDeleteError] = useState('')
  // AI가 제안한 구조화 결과를 사용자가 수정할 입력값 상태 설정.
  const [analysisForm, setAnalysisForm] = useState(() => createAnalysisForm())
  // 분석 결과 확정 API 요청 진행 여부 상태 설정.
  const [isConfirmingAnalysis, setIsConfirmingAnalysis] = useState(false)
  // 분석 결과 확정 성공 또는 실패 안내 상태 설정.
  const [analysisMessage, setAnalysisMessage] = useState('')
  // 분석 결과 확정 실패 여부 상태 설정.
  const [isAnalysisError, setIsAnalysisError] = useState(false)
  // AI 재분석 API 요청 진행 여부 상태 설정.
  const [isReanalyzing, setIsReanalyzing] = useState(false)
  // AI 재분석 결과 안내 문구 상태 설정.
  const [reanalysisMessage, setReanalysisMessage] = useState('')
  // 유사 CBT 사례 기반 패턴 설명 응답 상태 설정.
  const [patternExplanation, setPatternExplanation] = useState(null)
  // 패턴 설명 API 요청 진행 여부 상태 설정.
  const [isLoadingPattern, setIsLoadingPattern] = useState(false)
  // 패턴 설명 API 요청 실패 안내 문구 상태 설정.
  const [patternError, setPatternError] = useState('')

  // 화면 진입과 재조회 시 선택한 감정 기록의 상세 정보 요청.
  useEffect(() => {
    let isActive = true

    const loadEmotionRecordDetail = async () => {
      if (!emotionRecordId) {
        setIsLoading(false)
        setLoadError('조회할 감정 기록을 선택해 주세요.')
        return
      }

      setIsLoading(true)
      setLoadError('')

      try {
        const detail = await getEmotionRecordDetail(emotionRecordId)

        if (isActive) {
          setRecord(detail)
          setAnalysisForm(createAnalysisForm(detail))
          setOccurredAtInput(toDateTimeLocalValue(detail.occurredAt))
          setOccurredAtMessage('')
          setIsOccurredAtError(false)
          setAnalysisMessage('')
          setIsAnalysisError(false)
          setReanalysisMessage('')
          setPatternExplanation(null)
          setPatternError('')
        }
      } catch (error) {
        if (isActive) {
          setRecord(null)
          setLoadError(getDetailErrorMessage(error))
        }
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadEmotionRecordDetail()

    return () => {
      isActive = false
    }
  }, [emotionRecordId, reloadCount])

  // 보조 감정 목록의 코드와 강도를 한글 문구로 변환.
  const secondaryEmotionText = record?.secondaryEmotions?.length
    ? record.secondaryEmotions.map((emotion) => {
      const code = emotion.code ?? emotion.emotionCode ?? emotion.name
      const label = emotionCodeLabels[code] ?? code ?? '기타'
      const intensity = emotion.intensity ?? emotion.score

      return intensity === null || intensity === undefined
        ? label
        : `${label} ${intensity}/10`
    }).join(', ')
    : '분석 전'

  // 분석 확인 입력 항목의 변경값을 해당 필드에 반영.
  const handleAnalysisFieldChange = (event) => {
    const { name, value } = event.target

    setAnalysisForm((currentForm) => ({
      ...currentForm,
      [name]: value,
    }))
    setAnalysisMessage('')
    setIsAnalysisError(false)
  }

  // 선택한 보조 감정 입력 항목의 코드 또는 강도 변경값 반영.
  const handleSecondaryEmotionChange = (index, field, value) => {
    setAnalysisForm((currentForm) => ({
      ...currentForm,
      secondaryEmotions: currentForm.secondaryEmotions.map((emotion, emotionIndex) => (
        emotionIndex === index ? { ...emotion, [field]: value } : emotion
      )),
    }))
    setAnalysisMessage('')
    setIsAnalysisError(false)
  }

  // 사용자가 직접 확인할 새로운 보조 감정 입력 행 추가.
  const handleSecondaryEmotionAdd = () => {
    setAnalysisForm((currentForm) => ({
      ...currentForm,
      secondaryEmotions: [
        ...currentForm.secondaryEmotions,
        { code: '', intensity: '' },
      ],
    }))
  }

  // 사용자가 선택한 보조 감정 입력 행 제거.
  const handleSecondaryEmotionRemove = (index) => {
    setAnalysisForm((currentForm) => ({
      ...currentForm,
      secondaryEmotions: currentForm.secondaryEmotions.filter(
        (_, emotionIndex) => emotionIndex !== index,
      ),
    }))
  }

  // 사용자가 수정한 AI 분석 결과의 유효성을 확인하고 최종 확정 요청.
  const handleAnalysisConfirm = async (event) => {
    event.preventDefault()

    const primaryIntensity = analysisForm.primaryIntensity === ''
      ? null
      : Number(analysisForm.primaryIntensity)

    if (!analysisForm.primaryEmotionCode) {
      setAnalysisMessage('대표 감정을 선택해 주세요.')
      setIsAnalysisError(true)
      return
    }

    if (primaryIntensity !== null
      && (!Number.isInteger(primaryIntensity)
        || primaryIntensity < 0
        || primaryIntensity > 10)) {
      setAnalysisMessage('대표 감정 강도는 0부터 10 사이의 정수로 입력해 주세요.')
      setIsAnalysisError(true)
      return
    }

    const secondaryEmotions = analysisForm.secondaryEmotions
      .filter((emotion) => emotion.code)
      .map((emotion) => ({
        code: emotion.code,
        intensity: emotion.intensity === '' ? null : Number(emotion.intensity),
      }))

    const hasInvalidSecondaryIntensity = secondaryEmotions.some((emotion) => (
      emotion.intensity !== null
      && (!Number.isInteger(emotion.intensity)
        || emotion.intensity < 0
        || emotion.intensity > 10)
    ))

    if (hasInvalidSecondaryIntensity) {
      setAnalysisMessage('보조 감정 강도는 0부터 10 사이의 정수로 입력해 주세요.')
      setIsAnalysisError(true)
      return
    }

    setIsConfirmingAnalysis(true)
    setAnalysisMessage('')
    setIsAnalysisError(false)

    try {
      const confirmedRecord = await confirmEmotionRecord(emotionRecordId, {
        situationText: analysisForm.situationText.trim() || null,
        automaticThought: analysisForm.automaticThought.trim() || null,
        primaryEmotionCode: analysisForm.primaryEmotionCode,
        primaryIntensity,
        secondaryEmotions,
        contextCategory: analysisForm.situationText.trim()
          ? analysisForm.contextCategory || 'OTHER'
          : null,
        relatedPersonType: analysisForm.relatedPersonType || null,
        details: {
          ...(record.details ?? {}),
          interpretation: analysisForm.interpretation.trim() || null,
          bodyReaction: analysisForm.bodyReaction.trim() || null,
          behavior: analysisForm.behavior.trim() || null,
        },
      })

      setRecord(confirmedRecord)
      setAnalysisForm(createAnalysisForm(confirmedRecord))
      setAnalysisMessage('수정한 분석 결과를 최종 확정했습니다.')
    } catch (error) {
      setAnalysisMessage(getConfirmErrorMessage(error))
      setIsAnalysisError(true)
    } finally {
      setIsConfirmingAnalysis(false)
    }
  }

  // AI 분석 실패로 간편 기록 상태에 남은 기록의 재분석 요청.
  const handleReanalysis = async () => {
    if (isReanalyzing) return

    setIsReanalyzing(true)
    setReanalysisMessage('')
    setAnalysisMessage('')
    setIsAnalysisError(false)

    try {
      const reanalyzedRecord = await reanalyzeEmotionRecord(emotionRecordId)

      setRecord(reanalyzedRecord)
      setAnalysisForm(createAnalysisForm(reanalyzedRecord))
      setReanalysisMessage('AI 재분석을 완료했습니다. 제안된 내용을 확인해 주세요.')
    } catch (error) {
      setReanalysisMessage(getReanalysisErrorMessage(error))
    } finally {
      setIsReanalyzing(false)
    }
  }

  // 확정된 기록과 과거 CBT 사례를 기반으로 한 패턴 설명 요청.
  const handlePatternExplanation = async () => {
    if (isLoadingPattern) return

    setIsLoadingPattern(true)
    setPatternError('')

    try {
      const explanation = await getEmotionRecordPatternExplanation(emotionRecordId)

      setPatternExplanation(explanation)
    } catch (error) {
      setPatternExplanation(null)
      setPatternError(getPatternErrorMessage(error))
    } finally {
      setIsLoadingPattern(false)
    }
  }

  // 입력한 지역 시각을 UTC 형식으로 변환하여 감정 발생 시각 수정 요청.
  const handleOccurredAtUpdate = async (event) => {
    event.preventDefault()

    const selectedDate = new Date(occurredAtInput)

    if (!occurredAtInput || Number.isNaN(selectedDate.getTime())) {
      setOccurredAtMessage('수정할 날짜와 시간을 선택해 주세요.')
      setIsOccurredAtError(true)
      return
    }

    if (selectedDate.getTime() > Date.now()) {
      setOccurredAtMessage('현재보다 이후의 시간은 선택할 수 없습니다.')
      setIsOccurredAtError(true)
      return
    }

    setIsUpdatingOccurredAt(true)
    setOccurredAtMessage('')
    setIsOccurredAtError(false)

    try {
      const updatedRecord = await updateEmotionRecordOccurredAt(
        emotionRecordId,
        selectedDate.toISOString(),
      )

      setRecord(updatedRecord)
      setOccurredAtInput(toDateTimeLocalValue(updatedRecord.occurredAt))
      setOccurredAtMessage('감정 발생 시각을 수정했습니다.')
    } catch (error) {
      setOccurredAtMessage(getUpdateErrorMessage(error))
      setIsOccurredAtError(true)
    } finally {
      setIsUpdatingOccurredAt(false)
    }
  }

  // 사용자가 최종 확인한 감정 기록과 연결된 CBT 성찰 데이터 삭제 요청.
  const handleEmotionRecordDelete = async () => {
    if (isDeleting) return

    setIsDeleting(true)
    setDeleteError('')

    try {
      await deleteEmotionRecord(emotionRecordId)
      onBack()
    } catch (error) {
      setDeleteError(getDeleteErrorMessage(error))
      setIsDeleting(false)
    }
  }

  // 공통 네비게이션과 상세 조회 상태 및 결과 화면 반환.
  return (
    <main className="emotion-detail-page">
      {/* 인증 상태와 주요 화면 이동 기능을 제공하는 공통 상단 네비게이션 배치. */}
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

      {/* 선택한 감정 기록의 상세 정보를 담는 단일 테두리 콘텐츠 영역 배치. */}
      <section className="emotion-detail-content" aria-labelledby="emotion-detail-title">
        <BrandLogo className="emotion-detail-logo" onClick={onHome} />

        <div className="emotion-detail-heading">
          <div>
            <h1 id="emotion-detail-title">감정 기록 상세</h1>
            <p>선택한 날의 감정과 생각을 자세히 확인하는 공간입니다.</p>
          </div>
          <button type="button" onClick={onBack}>목록으로</button>
        </div>

        {isLoading ? (
          /* 감정 기록 상세 API 요청 중 사용자에게 진행 상태 안내. */
          <div className="emotion-detail-state" aria-live="polite" aria-busy="true">
            <h2>감정 기록을 불러오는 중입니다.</h2>
            <p>잠시만 기다려 주세요.</p>
          </div>
        ) : loadError ? (
          /* 감정 기록 상세 API 요청 실패 시 오류 원인과 재조회 기능 안내. */
          <div className="emotion-detail-state" role="alert">
            <h2>상세 정보를 불러오지 못했습니다.</h2>
            <p>{loadError}</p>
            <div className="emotion-detail-state-actions">
              <button type="button" onClick={onBack}>목록으로</button>
              {emotionRecordId && (
                <button
                  type="button"
                  onClick={() => setReloadCount((currentCount) => currentCount + 1)}
                >
                  다시 불러오기
                </button>
              )}
            </div>
          </div>
        ) : record ? (
          /* 상세 API 응답에서 받은 감정 기록 원문과 분석 정보 표시. */
          <div className="emotion-detail-result">
            <div className="emotion-detail-summary">
              <div className="emotion-detail-tags">
                <strong>
                  {emotionCodeLabels[record.primaryEmotionCode] ?? '분석 전'}
                </strong>
                <span>
                  {record.primaryIntensity === null || record.primaryIntensity === undefined
                    ? '강도 분석 전'
                    : `강도 ${record.primaryIntensity}/10`}
                </span>
                <span>
                  {contextCategoryLabels[record.contextCategory] ?? '상황 미분류'}
                </span>
              </div>
              <time dateTime={record.occurredAt}>
                {formatDetailDate(record.occurredAt)}
              </time>
            </div>

            {/* 실제 감정이 발생한 날짜와 시간을 수정하는 입력 영역 배치. */}
            <section
              className="emotion-detail-time-editor"
              aria-labelledby="emotion-detail-time-title"
            >
              <div>
                <h2 id="emotion-detail-time-title">감정 발생 시각</h2>
                <p>나중에 기록했다면 실제로 감정을 느낀 시각으로 변경해 주세요.</p>
              </div>
              <form onSubmit={handleOccurredAtUpdate}>
                <label htmlFor="emotion-detail-occurred-at">
                  <span>날짜와 시간</span>
                  <input
                    id="emotion-detail-occurred-at"
                    type="datetime-local"
                    value={occurredAtInput}
                    max={getCurrentDateTimeLocalValue()}
                    step="60"
                    disabled={isUpdatingOccurredAt || isDeleting}
                    aria-invalid={isOccurredAtError}
                    aria-describedby={occurredAtMessage
                      ? 'emotion-detail-time-message'
                      : undefined}
                    onChange={(event) => {
                      setOccurredAtInput(event.target.value)
                      setOccurredAtMessage('')
                      setIsOccurredAtError(false)
                    }}
                  />
                </label>
                <button
                  type="submit"
                  disabled={isUpdatingOccurredAt || isDeleting}
                >
                  {isUpdatingOccurredAt ? '수정 중' : '시각 수정하기'}
                </button>
              </form>
              {occurredAtMessage && (
                <p
                  id="emotion-detail-time-message"
                  className={isOccurredAtError ? 'is-error' : 'is-success'}
                  role={isOccurredAtError ? 'alert' : 'status'}
                >
                  {occurredAtMessage}
                </p>
              )}
            </section>

            <section className="emotion-detail-section" aria-labelledby="emotion-detail-raw-title">
              <h2 id="emotion-detail-raw-title">기록한 마음</h2>
              <p>{getDisplayValue(record.rawText, '작성한 내용이 없습니다.')}</p>
            </section>

            {/* AI 제안과 사용자가 확정한 분석 결과를 상태별로 구분하는 확인 영역 배치. */}
            <section
              className="emotion-detail-analysis"
              aria-labelledby="emotion-detail-analysis-title"
            >
              <div className="emotion-detail-analysis-heading">
                <div>
                  <h2 id="emotion-detail-analysis-title">감정 분석 결과</h2>
                  <p>
                    {record.completionStatus === 'COMPLETE'
                      ? '사용자가 확인하고 확정한 최종 분석 결과입니다.'
                      : 'AI가 원문을 바탕으로 제안한 결과를 확인해 주세요.'}
                  </p>
                </div>
                <strong className={`emotion-detail-analysis-source is-${record.completionStatus?.toLowerCase()}`}>
                  {record.completionStatus === 'COMPLETE' ? '사용자 확정값' : 'AI 제안'}
                </strong>
              </div>

              {record.completionStatus === 'QUICK' && (
                /* AI 분석에 실패한 기록에만 재분석 기능 표시. */
                <div className="emotion-detail-reanalysis">
                  <p>
                    아직 확인할 AI 분석 결과가 없습니다. 서버의 AI 분석이 가능할 때
                    다시 요청해 주세요.
                  </p>
                  <button
                    type="button"
                    onClick={handleReanalysis}
                    disabled={isReanalyzing || isDeleting}
                  >
                    {isReanalyzing ? '다시 분석하는 중' : '다시 분석하기'}
                  </button>
                </div>
              )}

              {reanalysisMessage && (
                <p
                  className={record.completionStatus === 'QUICK'
                    ? 'emotion-detail-analysis-message is-error'
                    : 'emotion-detail-analysis-message is-success'}
                  role={record.completionStatus === 'QUICK' ? 'alert' : 'status'}
                >
                  {reanalysisMessage}
                </p>
              )}

              {record.completionStatus === 'PARTIAL' && (
                /* AI 제안을 사용자가 직접 수정하고 확정하는 입력 양식 표시. */
                <form
                  className="emotion-detail-analysis-form"
                  onSubmit={handleAnalysisConfirm}
                >
                  <label className="emotion-detail-analysis-wide">
                    <span>상황</span>
                    <textarea
                      name="situationText"
                      rows="3"
                      value={analysisForm.situationText}
                      disabled={isConfirmingAnalysis || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    />
                  </label>

                  <label className="emotion-detail-analysis-wide">
                    <span>자동으로 떠오른 생각</span>
                    <textarea
                      name="automaticThought"
                      rows="3"
                      value={analysisForm.automaticThought}
                      disabled={isConfirmingAnalysis || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    />
                  </label>

                  <label>
                    <span>대표 감정</span>
                    <select
                      name="primaryEmotionCode"
                      value={analysisForm.primaryEmotionCode}
                      required
                      disabled={isConfirmingAnalysis || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    >
                      <option value="">감정 선택</option>
                      {Object.entries(emotionCodeLabels).map(([code, label]) => (
                        <option key={code} value={code}>{label}</option>
                      ))}
                    </select>
                  </label>

                  <label>
                    <span>대표 감정 강도</span>
                    <input
                      name="primaryIntensity"
                      type="number"
                      min="0"
                      max="10"
                      step="1"
                      value={analysisForm.primaryIntensity}
                      placeholder="0~10"
                      disabled={isConfirmingAnalysis || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    />
                  </label>

                  <label>
                    <span>상황 범주</span>
                    <select
                      name="contextCategory"
                      value={analysisForm.contextCategory}
                      disabled={isConfirmingAnalysis || isDeleting || !analysisForm.situationText.trim()}
                      onChange={handleAnalysisFieldChange}
                    >
                      <option value="">범주 선택</option>
                      {Object.entries(contextCategoryLabels).map(([code, label]) => (
                        <option key={code} value={code}>{label}</option>
                      ))}
                    </select>
                  </label>

                  <label>
                    <span>관련된 사람</span>
                    <select
                      name="relatedPersonType"
                      value={analysisForm.relatedPersonType}
                      disabled={isConfirmingAnalysis || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    >
                      <option value="">해당 없음</option>
                      {Object.entries(relatedPersonTypeLabels).map(([code, label]) => (
                        <option key={code} value={code}>{label}</option>
                      ))}
                    </select>
                  </label>

                  <fieldset className="emotion-detail-secondary-emotions">
                    <legend>함께 느낀 감정</legend>
                    {analysisForm.secondaryEmotions.length === 0 ? (
                      <p>AI가 제안한 보조 감정이 없습니다.</p>
                    ) : analysisForm.secondaryEmotions.map((emotion, index) => (
                      <div key={`${index}-${emotion.code}`}>
                        <select
                          aria-label={`보조 감정 ${index + 1}`}
                          value={emotion.code}
                          disabled={isConfirmingAnalysis || isDeleting}
                          onChange={(event) => handleSecondaryEmotionChange(
                            index,
                            'code',
                            event.target.value,
                          )}
                        >
                          <option value="">감정 선택</option>
                          {Object.entries(emotionCodeLabels).map(([code, label]) => (
                            <option key={code} value={code}>{label}</option>
                          ))}
                        </select>
                        <input
                          aria-label={`보조 감정 ${index + 1} 강도`}
                          type="number"
                          min="0"
                          max="10"
                          step="1"
                          value={emotion.intensity}
                          placeholder="강도 0~10"
                          disabled={isConfirmingAnalysis || isDeleting}
                          onChange={(event) => handleSecondaryEmotionChange(
                            index,
                            'intensity',
                            event.target.value,
                          )}
                        />
                        <button
                          type="button"
                          disabled={isConfirmingAnalysis || isDeleting}
                          onClick={() => handleSecondaryEmotionRemove(index)}
                        >
                          삭제
                        </button>
                      </div>
                    ))}
                    <button
                      className="emotion-detail-secondary-add"
                      type="button"
                      disabled={isConfirmingAnalysis || isDeleting}
                      onClick={handleSecondaryEmotionAdd}
                    >
                      보조 감정 추가
                    </button>
                  </fieldset>

                  <label className="emotion-detail-analysis-wide">
                    <span>해석</span>
                    <textarea
                      name="interpretation"
                      rows="2"
                      value={analysisForm.interpretation}
                      disabled={isConfirmingAnalysis || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    />
                  </label>

                  <label>
                    <span>신체 반응</span>
                    <textarea
                      name="bodyReaction"
                      rows="3"
                      value={analysisForm.bodyReaction}
                      disabled={isConfirmingAnalysis || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    />
                  </label>

                  <label>
                    <span>행동</span>
                    <textarea
                      name="behavior"
                      rows="3"
                      value={analysisForm.behavior}
                      disabled={isConfirmingAnalysis || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    />
                  </label>

                  <button
                    className="emotion-detail-analysis-confirm"
                    type="submit"
                    disabled={isConfirmingAnalysis || isDeleting}
                  >
                    {isConfirmingAnalysis ? '확정 중' : '수정한 결과 확정하기'}
                  </button>
                </form>
              )}

              {analysisMessage && (
                <p
                  className={`emotion-detail-analysis-message ${isAnalysisError ? 'is-error' : 'is-success'}`}
                  role={isAnalysisError ? 'alert' : 'status'}
                >
                  {analysisMessage}
                </p>
              )}
            </section>

            <dl className="emotion-detail-list">
              <div>
                <dt>기록 상태</dt>
                <dd>
                  {completionStatusLabels[record.completionStatus]
                    ?? getDisplayValue(record.completionStatus, '상태 확인 전')}
                </dd>
              </div>
              <div>
                <dt>기록 시간대</dt>
                <dd>{timeBucketLabels[record.timeBucket] ?? getDisplayValue(record.timeBucket)}</dd>
              </div>
              <div>
                <dt>상황</dt>
                <dd>{getDisplayValue(record.situationText)}</dd>
              </div>
              <div>
                <dt>자동으로 떠오른 생각</dt>
                <dd>{getDisplayValue(record.automaticThought)}</dd>
              </div>
              <div>
                <dt>함께 느낀 감정</dt>
                <dd>{secondaryEmotionText}</dd>
              </div>
              <div>
                <dt>관련된 사람</dt>
                <dd>{getDisplayValue(record.relatedPersonType)}</dd>
              </div>
              <div>
                <dt>해석</dt>
                <dd>{getDisplayValue(record.details?.interpretation)}</dd>
              </div>
              <div>
                <dt>신체 반응</dt>
                <dd>{getDisplayValue(record.details?.bodyReaction)}</dd>
              </div>
              <div>
                <dt>행동</dt>
                <dd>{getDisplayValue(record.details?.behavior)}</dd>
              </div>
            </dl>

            {/* 확정된 기록에 유사 CBT 사례 기반 패턴 설명 요청 및 결과 표시. */}
            {record.completionStatus === 'COMPLETE' && (
              <section
                className="emotion-detail-pattern"
                aria-labelledby="emotion-detail-pattern-title"
              >
                <div className="emotion-detail-pattern-heading">
                  <div>
                    <h2 id="emotion-detail-pattern-title">반복 패턴 설명</h2>
                    <p>완료한 과거 CBT 사례와 현재 기록의 유사한 흐름을 확인합니다.</p>
                  </div>
                  <button
                    type="button"
                    onClick={handlePatternExplanation}
                    disabled={isLoadingPattern || isDeleting}
                  >
                    {isLoadingPattern
                      ? '설명 생성 중'
                      : patternExplanation ? '다시 설명하기' : '패턴 설명 요청'}
                  </button>
                </div>

                {patternError && (
                  <p className="emotion-detail-pattern-error" role="alert">
                    {patternError}
                  </p>
                )}

                {patternExplanation && (
                  <div className="emotion-detail-pattern-result" role="status">
                    <p className="emotion-detail-pattern-count">
                      유사한 완료 사례 {patternExplanation.similarCaseCount}건을 참고했습니다.
                    </p>
                    <dl>
                      <div>
                        <dt>반복되는 흐름</dt>
                        <dd>{getDisplayValue(patternExplanation.patternSummary, '설명 없음')}</dd>
                      </div>
                      <div>
                        <dt>반복된 생각 패턴</dt>
                        <dd className="emotion-detail-pattern-codes">
                          {patternExplanation.repeatedDistortionCodes?.length
                            ? patternExplanation.repeatedDistortionCodes.map((code) => (
                              <span key={code}>{distortionCodeLabels[code] ?? code}</span>
                            ))
                            : '확인된 패턴 없음'}
                        </dd>
                      </div>
                      <div>
                        <dt>도움이 된 대안적 생각</dt>
                        <dd>{getDisplayValue(patternExplanation.helpfulAlternativeThought, '설명 없음')}</dd>
                      </div>
                      <div>
                        <dt>추천</dt>
                        <dd>{getDisplayValue(patternExplanation.recommendation, '설명 없음')}</dd>
                      </div>
                    </dl>
                  </div>
                )}
              </section>
            )}

            {/* 감정 기록과 연결된 CBT 성찰 데이터를 함께 삭제하는 위험 작업 영역 배치. */}
            <section
              className="emotion-detail-delete"
              aria-labelledby="emotion-detail-delete-title"
            >
              <div>
                <h2 id="emotion-detail-delete-title">감정 기록 삭제</h2>
                <p>더 이상 보관하지 않을 감정 기록을 삭제할 수 있습니다.</p>
              </div>

              {!isDeleteConfirmOpen ? (
                <button
                  className="emotion-detail-delete-open"
                  type="button"
                  onClick={() => {
                    setIsDeleteConfirmOpen(true)
                    setDeleteError('')
                  }}
                >
                  기록 삭제하기
                </button>
              ) : (
                /* 연결된 데이터 삭제 범위를 알리고 최종 선택을 받는 확인 영역 표시. */
                <div className="emotion-detail-delete-confirm" role="alert">
                  <strong>정말 삭제하시겠습니까?</strong>
                  <p>
                    이 기록과 연결된 CBT 성찰 데이터도 함께 삭제되며,
                    삭제한 내용은 복구할 수 없습니다.
                  </p>
                  <div>
                    <button
                      type="button"
                      onClick={() => {
                        setIsDeleteConfirmOpen(false)
                        setDeleteError('')
                      }}
                      disabled={isDeleting}
                    >
                      취소
                    </button>
                    <button
                      className="emotion-detail-delete-confirm-button"
                      type="button"
                      onClick={handleEmotionRecordDelete}
                      disabled={isDeleting}
                    >
                      {isDeleting ? '삭제 중' : '삭제 확인'}
                    </button>
                  </div>
                </div>
              )}

              {deleteError && (
                <p className="emotion-detail-delete-error" role="alert">
                  {deleteError}
                </p>
              )}
            </section>
          </div>
        ) : null}
      </section>
    </main>
  )
}

export default EmotionRecordDetail
