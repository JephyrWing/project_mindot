export const MAX_AUTOMATIC_THOUGHT_LENGTH = 4000
export const automaticThoughtError = (value) => {
  const text = value?.trim() ?? ''
  if (!text) return '자동으로 떠오른 생각을 입력해 주세요.'
  if (text.length > MAX_AUTOMATIC_THOUGHT_LENGTH)
    return `생각은 ${MAX_AUTOMATIC_THOUGHT_LENGTH}자 이내로 입력해 주세요.`
  return ''
}
