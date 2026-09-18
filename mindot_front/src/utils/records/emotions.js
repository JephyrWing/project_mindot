// Stored values are strings: known codes or an exact user-supplied name.
export const emotionCodeLabels = Object.freeze({
  ANXIETY: '불안', FEAR: '두려움', ANGER: '분노', FRUSTRATION: '답답함',
  SADNESS: '슬픔', DISAPPOINTMENT: '실망', SHAME: '수치심', GUILT: '죄책감',
  LONELINESS: '외로움', JOY: '기쁨', RELIEF: '안도', ACHIEVEMENT: '성취감',
  CALM: '평온', GRATITUDE: '감사', EXCITEMENT: '설렘', OTHER: '기타',
})
export const CUSTOM_EMOTION = '__CUSTOM_EMOTION__'
// Keep the stored custom name so existing 짜증 records remain in the same group.
export const primaryEmotionLabels = Object.freeze({ ...emotionCodeLabels, 짜증: '짜증' })
export const MAX_EMOTION_LENGTH = 50
export const isKnownEmotion = (value) => Object.hasOwn(emotionCodeLabels, value)
export const emotionLabel = (value, fallback = '분석 전', legacyOtherLabel = '기타') =>
  value === 'OTHER' ? legacyOtherLabel : isKnownEmotion(value) ? emotionCodeLabels[value] : value?.trim() ? value : fallback
export const normalizeEmotion = (value) => {
  const text = value?.trim() ?? ''
  return Object.entries(emotionCodeLabels).find(([, label]) => label === text)?.[0] ?? text
}
export const emotionSelection = (value) => ({
  primaryEmotionCode: value?.trim() ? Object.hasOwn(primaryEmotionLabels, value) ? value : CUSTOM_EMOTION : '',
  customEmotion: value?.trim() && !Object.hasOwn(primaryEmotionLabels, value) ? value : '',
})
export const selectedEmotion = (form) => normalizeEmotion(
  form.primaryEmotionCode === CUSTOM_EMOTION ? form.customEmotion : form.primaryEmotionCode,
)
export const emotionError = (value) => !value || value === CUSTOM_EMOTION
  ? '대표 감정 이름을 입력해 주세요.'
  : value.length > MAX_EMOTION_LENGTH ? '감정 이름은 50자 이내로 입력해 주세요.' : ''
