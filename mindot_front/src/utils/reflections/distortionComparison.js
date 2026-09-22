// 인지왜곡 라벨 배열에서 중복과 비확정 항목을 제거해 안정적인 코드 목록으로 변환.
export const normalizeDistortionCodes = (items = []) => {
  const codes = []

  for (const item of Array.isArray(items) ? items : []) {
    const code = typeof item === 'string' ? item : item?.code
    const reviewStatus = typeof item === 'string' ? 'CONFIRMED' : item?.reviewStatus

    if (!code || reviewStatus === 'REJECTED' || codes.includes(code)) continue
    codes.push(code)
  }

  return codes
}

// 새 형식의 BEFORE 목록을 우선 사용하고, 기존 결과는 확정된 AI 제안으로 복원.
export const getBeforeDistortionCodes = (result) => {
  if (Array.isArray(result?.beforeDistortions)) {
    return normalizeDistortionCodes(result.beforeDistortions)
  }

  const confirmedReviews = new Set(
    (result?.reviews ?? [])
      .filter((review) => review.reviewStatus === 'CONFIRMED')
      .map((review) => review.code),
  )

  return normalizeDistortionCodes(
    (result?.suggestions ?? []).filter((suggestion) => confirmedReviews.has(suggestion.code)),
  )
}

// 이전 결과에는 AFTER 라벨을 추정하지 않고 새 형식으로 저장된 값만 사용.
export const getAfterDistortionCodes = (result) => (
  Array.isArray(result?.afterDistortions)
    ? normalizeDistortionCodes(result.afterDistortions)
    : []
)

// BEFORE와 AFTER 집합을 비교해 제거·지속·신규 세 변화로 분류.
export const classifyDistortionChanges = (beforeCodes = [], afterCodes = []) => {
  const before = normalizeDistortionCodes(beforeCodes)
  const after = normalizeDistortionCodes(afterCodes)
  const beforeSet = new Set(before)
  const afterSet = new Set(after)

  return {
    removed: before.filter((code) => !afterSet.has(code)),
    persisted: before.filter((code) => afterSet.has(code)),
    new: after.filter((code) => !beforeSet.has(code)),
  }
}

