import { emotionLabel } from './emotions.js'

// Stored names remain intact; only the registered dropdown values have assigned colors.
export const emotionColors = Object.freeze({
  ANXIETY: '#ff7a90', FEAR: '#e96c93', ANGER: '#f95565', FRUSTRATION: '#ff9a7a',
  SADNESS: '#ed8fb2', DISAPPOINTMENT: '#ffb3a3', SHAME: '#d982af', GUILT: '#f68c80',
  LONELINESS: '#f7accb', 짜증: '#ff805f',
  JOY: '#45a9f8', RELIEF: '#81c9f4', ACHIEVEMENT: '#538bee', CALM: '#9cbfeb',
  GRATITUDE: '#59b7cd', EXCITEMENT: '#8a9bf5', OTHER: '#a4b1c2',
})
export const emotionColor = (value) => Object.hasOwn(emotionColors, value) ? emotionColors[value] : emotionColors.OTHER
export const reportEmotionLabel = (value) => emotionLabel(value, '감정 미입력')

// Only explicitly registered emotions are classified. Custom names/OTHER/missing stay neutral.
const negativeEmotions = new Set(['ANXIETY', 'FEAR', 'ANGER', 'FRUSTRATION', 'SADNESS',
  'DISAPPOINTMENT', 'SHAME', 'GUILT', 'LONELINESS', '짜증'])
const positiveEmotions = new Set(['JOY', 'RELIEF', 'ACHIEVEMENT', 'CALM', 'GRATITUDE', 'EXCITEMENT'])
export const emotionCategory = (value) => negativeEmotions.has(value) ? 'negative'
  : positiveEmotions.has(value) ? 'positive' : 'neutral'
