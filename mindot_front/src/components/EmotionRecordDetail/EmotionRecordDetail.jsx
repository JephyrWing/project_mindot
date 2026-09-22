import SafetyNoticeModal from '../SafetyNoticeModal/SafetyNoticeModal.jsx'
import useRecordPatternExplanation from '../../utils/records/useRecordPatternExplanation.js'
import { emotionCodeLabels, primaryEmotionLabels, emotionLabel, CUSTOM_EMOTION, MAX_EMOTION_LENGTH, emotionSelection, selectedEmotion, emotionError } from '../../utils/records/emotions.js'
import { automaticThoughtError, MAX_AUTOMATIC_THOUGHT_LENGTH } from '../../utils/reflections/automaticThought.js'
import { useCallback, useEffect, useRef, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import {
  confirmEmotionRecord,
  deleteEmotionRecord,
  getEmotionRecordDetail,
  getMissingInformationQuestions,
  rejectEmotionRecordAnalysis,
  reanalyzeEmotionRecord,
  updateEmotionRecord,
} from '../../utils/records/recordsApi.js'
import './EmotionRecordDetail.css'

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
  rawText: record?.rawText ?? '',
  situationText: record?.situationText ?? '',
  automaticThought: record?.automaticThought ?? '',
  ...emotionSelection(record?.primaryEmotionCode),
  primaryIntensity: record?.primaryIntensity ?? '',
  secondaryEmotions: (record?.secondaryEmotions ?? []).map((emotion) => ({
    code: emotion.code ?? emotion.emotionCode ?? emotion.name ?? '',
    intensity: emotion.intensity ?? emotion.score ?? '',
  })),
  contextCategory: record?.contextCategory ?? '',
  relatedPersonType: record?.relatedPersonType ?? '',
  bodyReaction: record?.details?.bodyReaction ?? '',
  behavior: record?.details?.behavior ?? '',
})

// 비어 있는 상세 항목에 공통으로 표시할 안내 문구 반환.
const getDisplayValue = (value, fallback = '-') => (
  value === null || value === undefined || (typeof value === 'string' && !value.trim())
    ? fallback : value
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
    return 'CBT가 시작되었거나 기록 상태가 변경되어 저장할 수 없습니다. 새로고침 후 확인해 주세요.'
  }

  return '분석 결과를 확정하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 누락 정보 보완 질문 조회 API 오류 상태에 따른 사용자 안내 문구 반환.
const getMissingQuestionsErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없어 보완 질문을 불러오지 못했습니다.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되어 보완 질문을 불러오지 못했습니다.'
  }
  if (error.response.status === 404) {
    return '보완 질문을 조회할 감정 기록을 찾을 수 없습니다.'
  }
  if (error.response.status === 409) {
    return '현재 기록 상태에서는 보완 질문을 조회할 수 없습니다.'
  }

  return '보완 질문을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// AI 분석 제안 거절 API 오류 상태에 따른 사용자 안내 문구 반환.
const getRejectErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (error.response.status === 404) {
    return '거절할 감정 기록을 찾을 수 없습니다.'
  }
  if (error.response.status === 409) {
    return '이미 처리되었거나 현재 거절할 수 없는 AI 제안입니다. 새로고침 후 확인해 주세요.'
  }

  return 'AI 제안을 거절하지 못했습니다. 잠시 후 다시 시도해 주세요.'
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

// 선택한 감정 기록 한 건을 API로 조회하고 상세 정보를 제공하는 화면 정의.
function EmotionRecordDetail({
  emotionRecordId,
  initialSavedRecord,
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onDailyCare,
  onCBT,
  backLabel = '목록으로',
  onBack,
  onHome,
}) {
  const analysisRef = useRef(null)
  const confirming = useRef(false)
  const rejecting = useRef(false)
  const reanalyzing = useRef(false)
  const focused = useRef(false)
  const [safetyNotice, setSafetyNotice] = useState(initialSavedRecord?.safetyNotice ?? null)
  const shownSafety = useRef(initialSavedRecord?.safetyNotice?.safetyEventId)
  const closeSafety = useCallback(() => setSafetyNotice(null), [])
  const showSafety = useCallback((notice) => {
    if (notice && (!shownSafety.current || notice.safetyEventId !== shownSafety.current)) {
      shownSafety.current = notice.safetyEventId
      setSafetyNotice(notice)
    }
  }, [])
  const isWeeklyReportReturn = backLabel === '주간 리포트로 돌아가기'
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
  // 사용자의 감정 기록 삭제 확인 영역 표시 여부 상태 설정.
  const [isDeleteConfirmOpen, setIsDeleteConfirmOpen] = useState(false)
  // 감정 기록 삭제 API 요청 진행 여부 상태 설정.
  const [isDeleting, setIsDeleting] = useState(false)
  // 감정 기록 삭제 API 요청 실패 안내 문구 상태 설정.
  const [deleteError, setDeleteError] = useState('')
  // AI가 제안한 구조화 결과를 사용자가 수정할 입력값 상태 설정.
  const [analysisForm, setAnalysisForm] = useState(() => createAnalysisForm())
  const [isEditing, setIsEditing] = useState(false)
  // AI 구조화 결과의 누락 항목과 연결된 보완 질문 조회 상태 설정.
  const [missingQuestions, setMissingQuestions] = useState([])
  const [isLoadingMissingQuestions, setIsLoadingMissingQuestions] = useState(false)
  const [missingQuestionsError, setMissingQuestionsError] = useState('')
  const [missingQuestionsReloadCount, setMissingQuestionsReloadCount] = useState(0)
  // 분석 결과 확정 API 요청 진행 여부 상태 설정.
  const [isConfirmingAnalysis, setIsConfirmingAnalysis] = useState(false)
  // AI 분석 제안 거절 API 요청 진행 여부 상태 설정.
  const [isRejectingAnalysis, setIsRejectingAnalysis] = useState(false)
  const isAnalysisActionPending = isConfirmingAnalysis || isRejectingAnalysis
  // 분석 결과 확정 성공 또는 실패 안내 상태 설정.
  const [analysisMessage, setAnalysisMessage] = useState('')
  // 분석 결과 확정 실패 여부 상태 설정.
  const [isAnalysisError, setIsAnalysisError] = useState(false)
  // AI 재분석 API 요청 진행 여부 상태 설정.
  const [isReanalyzing, setIsReanalyzing] = useState(false)
  // AI 재분석 결과 안내 문구 상태 설정.
  const [reanalysisMessage, setReanalysisMessage] = useState('')
  const [isReanalysisError, setIsReanalysisError] = useState(false)
  const patternExplanation = useRecordPatternExplanation(emotionRecordId, record,
    !isLoading && !loadError && !isEditing && !isConfirmingAnalysis && !isRejectingAnalysis
      && !isDeleting && !safetyNotice
      && (record?.safetyNotice ?? initialSavedRecord?.safetyNotice)?.actionCode !== 'SHOW_CRISIS_NOTICE')
  const patternNoticeRef = useRef(null)
  const announcedPattern = useRef(null)
  useEffect(() => {
    if (!patternExplanation || announcedPattern.current === patternExplanation) return
    announcedPattern.current = patternExplanation
    patternNoticeRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }, [patternExplanation])
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
          setIsEditing(false)
          showSafety(detail.safetyNotice)
          setAnalysisForm(createAnalysisForm(detail))
          setOccurredAtInput(toDateTimeLocalValue(detail.occurredAt))
          setAnalysisMessage('')
          setIsAnalysisError(false)
          setReanalysisMessage('')
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
  }, [emotionRecordId, reloadCount, showSafety])

  // AI 구조화가 끝난 PARTIAL 기록에서만 누락 정보 보완 질문을 조회.
  useEffect(() => {
    if (!emotionRecordId || record?.completionStatus !== 'PARTIAL') {
      return undefined
    }

    let isActive = true

    const loadMissingQuestions = async () => {
      setIsLoadingMissingQuestions(true)
      setMissingQuestionsError('')

      try {
        const response = await getMissingInformationQuestions(emotionRecordId)

        if (isActive) {
          setMissingQuestions(Array.isArray(response?.questions) ? response.questions : [])
        }
      } catch (error) {
        if (isActive) {
          setMissingQuestions([])
          setMissingQuestionsError(getMissingQuestionsErrorMessage(error))
        }
      } finally {
        if (isActive) setIsLoadingMissingQuestions(false)
      }
    }

    loadMissingQuestions()

    return () => {
      isActive = false
    }
  }, [emotionRecordId, record?.completionStatus, missingQuestionsReloadCount])

  // Only read the already saved record. Never create/reanalyze while polling.
  const analysisPending = ['PENDING', 'PROCESSING'].includes(record?.analysisStatus)
  useEffect(() => {
    if (!analysisPending || loadError || isLoading) return
    let active = true
    let timer
    const poll = async () => {
      try {
        const detail = await getEmotionRecordDetail(emotionRecordId)
        if (!active) return
        setRecord(detail)
        showSafety(detail.safetyNotice)
        if (detail.completionStatus === 'PARTIAL') {
          setAnalysisForm(createAnalysisForm(detail))
          setReanalysisMessage('')
        }
        if (['PENDING', 'PROCESSING'].includes(detail.analysisStatus)) timer = setTimeout(poll, 2000)
      } catch (error) {
        if (active) setLoadError(getDetailErrorMessage(error))
      }
    }
    timer = setTimeout(poll, 2000)
    return () => { active = false; clearTimeout(timer) }
  }, [emotionRecordId, analysisPending, loadError, isLoading, showSafety])

  useEffect(() => {
    if (!initialSavedRecord || focused.current || safetyNotice || isLoading || loadError
      || record?.completionStatus !== 'PARTIAL') return
    focused.current = true
    analysisRef.current?.scrollIntoView({ block: 'start' })
    analysisRef.current?.querySelector('textarea')?.focus({ preventScroll: true })
  }, [initialSavedRecord, record?.completionStatus, safetyNotice, isLoading, loadError])

  // 보조 감정 목록의 코드와 강도를 한글 문구로 변환.
  const secondaryEmotionText = record?.secondaryEmotions?.length
    ? record.secondaryEmotions.map((emotion) => {
      const code = emotion.code ?? emotion.emotionCode ?? emotion.name
      const label = emotionLabel(code, '-')
      const intensity = emotion.intensity ?? emotion.score

      return intensity === null || intensity === undefined
        ? label
        : `${label} ${intensity}/10`
    }).join(', ')
    : '-'

  // 편집 중에는 현재 선택/직접 입력값을 표시하고, 확정 후에는 저장값을 표시.
  const primaryEmotionText = isEditing || record?.completionStatus === 'PARTIAL'
    ? selectedEmotion(analysisForm)
    : record?.primaryEmotionCode

  // 수정 화면에서는 입력 중인 자동 사고를 상세 요약에도 즉시 반영하는 문구 설정.
  const automaticThoughtText = isEditing || record?.completionStatus === 'PARTIAL'
    ? analysisForm.automaticThought.trim()
    : record?.automaticThought?.trim()

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

  // 선택한 보완 질문과 연결된 기존 분석 입력칸으로 이동해 바로 답할 수 있도록 처리.
  const focusMissingQuestionField = (fieldName) => {
    const field = analysisRef.current?.querySelector(`[name="${fieldName}"]`)

    field?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    field?.focus({ preventScroll: true })
  }

  // Missing thoughts are collected and durably saved in the CBT start flow.
  const handleCbtStart = () => onCBT(emotionRecordId)

  // 사용자가 수정한 AI 분석 결과의 유효성을 확인하고 최종 확정 요청.
  const handleAnalysisConfirm = async (event) => {
    event.preventDefault()
    if (confirming.current || rejecting.current || record.cbtStarted) return
    if (isEditing && !analysisForm.rawText.trim()) {
      setAnalysisMessage('기록 원문을 입력해 주세요.')
      setIsAnalysisError(true)
      return
    }
    const editedDate = new Date(occurredAtInput)
    if (isEditing && (!occurredAtInput || Number.isNaN(editedDate.getTime()) || editedDate.getTime() > Date.now())) {
      setAnalysisMessage('현재 또는 과거의 날짜와 시간을 입력해 주세요.')
      setIsAnalysisError(true)
      return
    }

    const primaryIntensity = analysisForm.primaryIntensity === ''
      ? null
      : Number(analysisForm.primaryIntensity)

    const thoughtError = automaticThoughtError(analysisForm.automaticThought, { required: false })
    const primaryEmotionCode = selectedEmotion(analysisForm)
    const validationError = thoughtError || emotionError(primaryEmotionCode)
    if (validationError) {
      setAnalysisMessage(validationError)
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

    confirming.current = true
    setIsConfirmingAnalysis(true)
    setAnalysisMessage('')
    setIsAnalysisError(false)

    try {
      const analysis = {
        situationText: analysisForm.situationText.trim() || null,
        automaticThought: analysisForm.automaticThought.trim() || null,
        primaryEmotionCode,
        primaryIntensity,
        secondaryEmotions,
        contextCategory: analysisForm.situationText.trim()
          ? analysisForm.contextCategory || 'OTHER'
          : null,
        relatedPersonType: analysisForm.relatedPersonType || null,
        details: {
          ...(record.details ?? {}),
          bodyReaction: analysisForm.bodyReaction.trim() || null,
          behavior: analysisForm.behavior.trim() || null,
        },
      }
      const confirmedRecord = isEditing
        ? await updateEmotionRecord(emotionRecordId, {
          rawText: analysisForm.rawText.trim(),
          occurredAt: occurredAtInput === toDateTimeLocalValue(record.occurredAt)
            ? record.occurredAt : editedDate.toISOString(),
          analysis,
        })
        : await confirmEmotionRecord(emotionRecordId, analysis)

      setRecord(confirmedRecord)
      setAnalysisForm(createAnalysisForm(confirmedRecord))
      setOccurredAtInput(toDateTimeLocalValue(confirmedRecord.occurredAt))
      setAnalysisMessage(isEditing ? '감정 기록을 수정했습니다.' : '수정한 분석 결과를 최종 확정했습니다.')
      setIsEditing(false)
    } catch (error) {
      setAnalysisMessage(getConfirmErrorMessage(error))
      setIsAnalysisError(true)
    } finally {
      confirming.current = false
      setIsConfirmingAnalysis(false)
    }
  }

  // AI가 제안한 구조화 값은 사용하지 않고 작성한 원문만 간편 기록으로 유지한다.
  const handleAnalysisReject = async () => {
    if (rejecting.current || confirming.current || record.cbtStarted) return
    rejecting.current = true
    setIsRejectingAnalysis(true)
    setAnalysisMessage('')
    setIsAnalysisError(false)

    try {
      const rejectedRecord = await rejectEmotionRecordAnalysis(emotionRecordId)

      setRecord(rejectedRecord)
      setAnalysisForm(createAnalysisForm(rejectedRecord))
      setAnalysisMessage('AI 제안을 거절했습니다. 작성한 원문은 그대로 저장됩니다.')
    } catch (error) {
      setAnalysisMessage(getRejectErrorMessage(error))
      setIsAnalysisError(true)
    } finally {
      rejecting.current = false
      setIsRejectingAnalysis(false)
    }
  }

  // AI 분석 실패로 간편 기록 상태에 남은 기록의 재분석 요청.
  const handleReanalysis = async () => {
    if (reanalyzing.current) return
    reanalyzing.current = true

    setIsReanalyzing(true)
    setIsReanalysisError(false)
    setReanalysisMessage('')
    setAnalysisMessage('')
    setIsAnalysisError(false)

    try {
      const reanalyzedRecord = await reanalyzeEmotionRecord(emotionRecordId)

      setRecord(reanalyzedRecord)
      showSafety(reanalyzedRecord.safetyNotice)
      setAnalysisForm(createAnalysisForm(reanalyzedRecord))
      setReanalysisMessage(reanalyzedRecord.completionStatus === 'QUICK'
          ? reanalyzedRecord.analysisStatus === 'FAILED'
            ? '원문은 저장되었습니다. AI 분석에 실패했습니다. 다시 분석할 수 있습니다.'
            : '원문은 저장되었습니다. 분석 상태를 확인하고 있습니다.'
          : 'AI 재분석을 완료했습니다. 제안된 내용을 확인해 주세요.')
    } catch (error) {
      setIsReanalysisError(true)
      setReanalysisMessage(getReanalysisErrorMessage(error))
    } finally {
      reanalyzing.current = false
      setIsReanalyzing(false)
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
          <div className="emotion-detail-heading-actions">
            {!isWeeklyReportReturn && <button type="button" onClick={onBack}>{backLabel}</button>}
            {!isLoading && !loadError && record?.completionStatus === 'COMPLETE' && !record.cbtStarted && !isEditing && (
              <button className="emotion-detail-edit-button" type="button" disabled={isDeleting}
                onClick={() => {
                  setAnalysisForm(createAnalysisForm(record))
                  setOccurredAtInput(toDateTimeLocalValue(record.occurredAt))
                  setAnalysisMessage('')
                  setIsAnalysisError(false)
                  setIsEditing(true)
                }}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                  strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="m15 5 4 4M4 20l4-1L20 7a2.8 2.8 0 0 0-4-4L4 15v5Z" />
                </svg>
                수정하기
              </button>
            )}
          </div>
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
            <p>기존 기록을 다시 조회합니다. 원문을 새로 저장할 필요가 없습니다.</p>
            <div className="emotion-detail-state-actions">
              <button type="button" onClick={onBack}>{backLabel}</button>
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
                  {emotionLabel(primaryEmotionText, '-')}
                </strong>
                <span>
                  {record.primaryIntensity === null || record.primaryIntensity === undefined
                    ? '-'
                    : `강도 ${record.primaryIntensity}/10`}
                </span>
                <span>
                  {contextCategoryLabels[record.contextCategory] ?? getDisplayValue(record.contextCategory)}
                </span>
              </div>
              <time dateTime={record.occurredAt}>
                {formatDetailDate(record.occurredAt)}
              </time>
            </div>

            {/* 확정 완료 CBT에서 찾은 결과만 조용히 표시. */}
            {patternExplanation && (
              <section
                ref={patternNoticeRef}
                className="emotion-detail-pattern emotion-detail-pattern-notice"
                aria-labelledby="emotion-detail-pattern-title"
              >
                <div className="emotion-detail-pattern-heading">
                  <div>
                    <h2 id="emotion-detail-pattern-title">반복 패턴 알림</h2>
                    <p>내가 확인한 과거 CBT 기록에서 비슷한 흐름을 찾았어요.</p>
                  </div>

                </div>

                  <div className="emotion-detail-pattern-result" role="status">
                    <p className="emotion-detail-pattern-count">
                      유사한 완료 사례 {patternExplanation.similarCaseCount}건을 참고했습니다.
                    </p>
                    <dl>
                      <div>
                        <dt>반복되는 흐름</dt>
                        <dd>{getDisplayValue(patternExplanation.patternSummary, '설명 없음')}</dd>
                      </div>
                      {patternExplanation.repeatedDistortionCodes?.length > 0 && <div>
                        <dt>반복된 생각 패턴</dt>
                        <dd className="emotion-detail-pattern-codes">
                          {patternExplanation.repeatedDistortionCodes.map((code) => (
                              <span key={code}>{distortionCodeLabels[code] ?? code}</span>
                            ))}
                        </dd>
                      </div>}
                      {patternExplanation.helpfulAlternativeThought?.trim() && <div>
                        <dt>도움이 된 대안적 생각</dt>
                        <dd>{patternExplanation.helpfulAlternativeThought}</dd>
                      </div>}
                      {patternExplanation.recommendation?.trim() && <div>
                        <dt>추천</dt>
                        <dd>{patternExplanation.recommendation}</dd>
                      </div>}
                    </dl>
                  </div>
              </section>
            )}

            <section className="emotion-detail-section" aria-labelledby="emotion-detail-raw-title">
              <h2 id="emotion-detail-raw-title">기록한 마음</h2>
              <p>{getDisplayValue(record.rawText, '작성한 내용이 없습니다.')}</p>
            </section>

            {/* AI 제안과 사용자가 확정한 분석 결과를 상태별로 구분하는 확인 영역 배치. */}
            <section
              ref={analysisRef}
              className="emotion-detail-analysis"
              aria-labelledby="emotion-detail-analysis-title"
            >
              <div className="emotion-detail-analysis-heading">
                <div>
                  <h2 id="emotion-detail-analysis-title">감정 분석 결과</h2>
                  <p>
                    {record.completionStatus === 'COMPLETE'
                      ? '사용자가 확인하고 확정한 최종 분석 결과입니다.'
                      : record.analysisStatus === 'REJECTED'
                        ? 'AI 제안은 거절되었으며 작성한 원문만 저장되어 있습니다.'
                      : 'AI가 원문을 바탕으로 제안한 결과를 확인해 주세요.'}
                  </p>
                </div>
                <strong className={`emotion-detail-analysis-source is-${record.completionStatus?.toLowerCase()}`}>
                  {record.completionStatus === 'COMPLETE'
                    ? '사용자 확정값'
                    : record.analysisStatus === 'REJECTED' ? '제안 거절됨' : 'AI 제안'}
                </strong>
              </div>

              {record.cbtStarted && (
                <p className="emotion-detail-edit-notice" role="status">CBT가 시작된 기록은 수정할 수 없습니다.</p>
              )}

              {record.completionStatus === 'QUICK' && record.analysisStatus === 'FAILED' && (
                /* AI 분석에 실패한 기록에만 재분석 기능 표시. */
                <div className="emotion-detail-reanalysis">
                  <p>
                    원문은 저장되었습니다. AI 분석에 실패했습니다. 다시 분석할 수 있습니다.
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

              {record.completionStatus === 'QUICK' && record.analysisStatus === 'REJECTED' && (
                <div className="emotion-detail-reanalysis">
                  <p>
                    작성한 원문은 그대로 저장되어 있습니다. 필요하면 AI 분석을 다시 요청할 수 있습니다.
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

              {record.completionStatus === 'QUICK'
                && !['FAILED', 'REJECTED'].includes(record.analysisStatus) && (
                <div className="emotion-detail-reanalysis" role="status">
                  <p>{analysisPending
                    ? '원문은 저장되었습니다. AI 분석 중이며 완료 상태를 자동으로 확인합니다.'
                    : '원문은 저장되었습니다. 분석 상태를 확인하지 못했습니다.'}</p>
                  {!analysisPending && <button type="button" onClick={() => setReloadCount((n) => n + 1)}>분석 상태 다시 확인</button>}
                </div>
              )}
              {(record.safetyNotice ?? initialSavedRecord?.safetyNotice)?.actionCode === 'SHOW_CRISIS_NOTICE' && (
                <p role="status">현재는 CBT 성찰보다 즉시 안전을 확인하고 주변 또는 전문기관에 도움을 요청하는 일이 우선입니다.</p>
              )}

              {reanalysisMessage && (
                <p
                  className={isReanalysisError || record.analysisStatus === 'FAILED'
                    ? 'emotion-detail-analysis-message is-error'
                    : 'emotion-detail-analysis-message is-success'}
                  role={isReanalysisError || record.analysisStatus === 'FAILED' ? 'alert' : 'status'}
                >
                  {reanalysisMessage}
                </p>
              )}

              {!record.cbtStarted && (isEditing || record.completionStatus === 'PARTIAL') && (
                /* AI 제안을 사용자가 직접 수정하고 확정하는 입력 양식 표시. */
                <form
                  className="emotion-detail-analysis-form"
                  onSubmit={handleAnalysisConfirm}
                >
                  {record.completionStatus === 'PARTIAL' && (
                    <section
                      className="emotion-detail-missing-questions"
                      aria-labelledby="emotion-detail-missing-questions-title"
                    >
                      <div>
                        <h3 id="emotion-detail-missing-questions-title">조금 더 알려 주세요</h3>
                        <p>AI가 찾지 못한 내용을 보완하면 기록을 더 정확하게 남길 수 있습니다.</p>
                      </div>

                      {isLoadingMissingQuestions && (
                        <p className="emotion-detail-missing-questions-state" role="status">
                          보완 질문을 확인하고 있습니다.
                        </p>
                      )}

                      {!isLoadingMissingQuestions && missingQuestionsError && (
                        <div className="emotion-detail-missing-questions-error">
                          <p>{missingQuestionsError}</p>
                          <button
                            type="button"
                            onClick={() => setMissingQuestionsReloadCount((count) => count + 1)}
                          >
                            다시 불러오기
                          </button>
                        </div>
                      )}

                      {!isLoadingMissingQuestions && !missingQuestionsError && missingQuestions.length > 0 && (
                        <ol>
                          {missingQuestions.map((missingQuestion) => (
                            <li key={missingQuestion.fieldName}>
                              <button
                                type="button"
                                onClick={() => focusMissingQuestionField(missingQuestion.fieldName)}
                              >
                                <span>{missingQuestion.question}</span>
                                <small>{missingQuestion.required ? '필수 답변' : '선택 답변'}</small>
                              </button>
                            </li>
                          ))}
                        </ol>
                      )}

                      {!isLoadingMissingQuestions && !missingQuestionsError && missingQuestions.length === 0 && (
                        <p className="emotion-detail-missing-questions-state">
                          추가로 보완할 항목이 없습니다. 아래 내용을 확인한 뒤 확정해 주세요.
                        </p>
                      )}
                    </section>
                  )}
                  {isEditing && <label>
                    <span>날짜와 시간</span>
                    <input type="datetime-local" value={occurredAtInput} required
                      max={getCurrentDateTimeLocalValue()} step="60"
                      disabled={isAnalysisActionPending || isDeleting}
                      onChange={(event) => setOccurredAtInput(event.target.value)} />
                  </label>}
                  {isEditing && <label className="emotion-detail-analysis-wide">
                    <span>기록 원문</span>
                    <textarea name="rawText" rows="4" required value={analysisForm.rawText}
                      disabled={isAnalysisActionPending || isDeleting} onChange={handleAnalysisFieldChange} />
                  </label>}
                  <label className="emotion-detail-analysis-wide">
                    <span>상황</span>
                    <textarea
                      name="situationText"
                      rows="3"
                      value={analysisForm.situationText}
                      disabled={isAnalysisActionPending || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    />
                  </label>

                  <label className="emotion-detail-analysis-wide">
                    <span>자동으로 떠오른 생각 (선택)</span>
                    <textarea
                      name="automaticThought"
                      maxLength={MAX_AUTOMATIC_THOUGHT_LENGTH}
                      rows="3"
                      value={analysisForm.automaticThought}
                      disabled={isAnalysisActionPending || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    />
                  </label>

                  <label>
                    <span>대표 감정</span>
                    <select
                      name="primaryEmotionCode"
                      value={analysisForm.primaryEmotionCode}
                      required
                      disabled={isAnalysisActionPending || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    >
                      <option value="">감정 선택</option>
                      {Object.entries(primaryEmotionLabels).filter(([code]) => code !== 'OTHER').map(([code, label]) => (
                        <option key={code} value={code}>{label}</option>
                      ))}
                      {analysisForm.primaryEmotionCode === 'OTHER' && <option value="OTHER">기타 (기존 기록)</option>}
                      <option value={CUSTOM_EMOTION}>직접 입력</option>
                    </select>
                    {analysisForm.primaryEmotionCode === CUSTOM_EMOTION && (
                      <input aria-label="직접 입력 감정 이름" name="customEmotion"
                        value={analysisForm.customEmotion} maxLength={MAX_EMOTION_LENGTH} required
                        placeholder="감정 이름 (50자 이내)"
                        disabled={isAnalysisActionPending || isDeleting} onChange={handleAnalysisFieldChange} />
                    )}
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
                      disabled={isAnalysisActionPending || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    />
                  </label>

                  <label>
                    <span>상황 범주</span>
                    <select
                      name="contextCategory"
                      value={analysisForm.contextCategory}
                      disabled={isAnalysisActionPending || isDeleting || !analysisForm.situationText.trim()}
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
                      disabled={isAnalysisActionPending || isDeleting}
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
                          disabled={isAnalysisActionPending || isDeleting}
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
                          disabled={isAnalysisActionPending || isDeleting}
                          onChange={(event) => handleSecondaryEmotionChange(
                            index,
                            'intensity',
                            event.target.value,
                          )}
                        />
                        <button
                          type="button"
                          disabled={isAnalysisActionPending || isDeleting}
                          onClick={() => handleSecondaryEmotionRemove(index)}
                        >
                          삭제
                        </button>
                      </div>
                    ))}
                    <button
                      className="emotion-detail-secondary-add"
                      type="button"
                      disabled={isAnalysisActionPending || isDeleting}
                      onClick={handleSecondaryEmotionAdd}
                    >
                      보조 감정 추가
                    </button>
                  </fieldset>

                  <label>
                    <span>신체 반응</span>
                    <textarea
                      name="bodyReaction"
                      rows="3"
                      value={analysisForm.bodyReaction}
                      disabled={isAnalysisActionPending || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    />
                  </label>

                  <label>
                    <span>행동</span>
                    <textarea
                      name="behavior"
                      rows="3"
                      value={analysisForm.behavior}
                      disabled={isAnalysisActionPending || isDeleting}
                      onChange={handleAnalysisFieldChange}
                    />
                  </label>

                  <div className="emotion-detail-edit-actions">
                    {isEditing && <button className="emotion-detail-edit-cancel" type="button" disabled={isAnalysisActionPending || isDeleting}
                      onClick={() => {
                        setIsEditing(false)
                        setAnalysisForm(createAnalysisForm(record))
                        setOccurredAtInput(toDateTimeLocalValue(record.occurredAt))
                        setAnalysisMessage('')
                      }}>수정 취소</button>}
                    {!isEditing && <button
                      className="emotion-detail-analysis-reject"
                      type="button"
                      disabled={isAnalysisActionPending || isDeleting}
                      onClick={handleAnalysisReject}
                    >
                      {isRejectingAnalysis ? '거절 중' : 'AI 제안 거절하기'}
                    </button>}
                    <button
                      className="emotion-detail-analysis-confirm"
                      type="submit"
                      disabled={isAnalysisActionPending || isDeleting}
                    >
                      {isConfirmingAnalysis ? '저장 중' : isEditing ? '수정 내용 저장하기' : '수정한 결과 확정하기'}
                    </button>
                  </div>
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

              {/* 사용자 확정을 마친 기록의 CBT 시작 버튼 표시. */}
              {record.completionStatus === 'COMPLETE' && (
                <button
                  className="emotion-detail-cbt-button"
                  type="button"
                  onClick={handleCbtStart}
                  disabled={isDeleting || isEditing || isConfirmingAnalysis}
                >
                  CBT 검사 하기
                </button>
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
                <dd>{getDisplayValue(automaticThoughtText)}</dd>
              </div>
              <div>
                <dt>감정</dt>
                <dd>{emotionLabel(primaryEmotionText, '-')}</dd>
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
                <dt>신체 반응</dt>
                <dd>{getDisplayValue(record.details?.bodyReaction)}</dd>
              </div>
              <div>
                <dt>행동</dt>
                <dd>{getDisplayValue(record.details?.behavior)}</dd>
              </div>
            </dl>

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

            {isWeeklyReportReturn && (
              <button
                className="emotion-detail-weekly-back"
                type="button"
                onClick={onBack}
              >
                주간 리포트로 돌아가기
              </button>
            )}
          </div>
        ) : null}
      </section>
      {safetyNotice && <SafetyNoticeModal notice={safetyNotice} onClose={closeSafety} />}
    </main>
  )
}

export default EmotionRecordDetail
