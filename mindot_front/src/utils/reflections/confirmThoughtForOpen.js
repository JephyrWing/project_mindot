// Resolve the durable record stage before starting/retrying the separate OPEN.
export async function confirmThoughtForOpen(id, payload, api) {
  const confirmed = (record) => {
    if (record.completionStatus !== 'COMPLETE') return false
    if (record.automaticThought !== payload.automaticThought) {
      const error = new Error('confirmed_thought_conflict')
      error.userMessage = '기록이 다른 생각으로 확정되어 있습니다. 기록 상세에서 저장된 내용을 확인해 주세요.'
      throw error
    }
    return true
  }
  const saved = await api.getEmotionRecordDetail(id)
  if (confirmed(saved)) return saved
  // The server retains its PARTIAL-only confirmation rule.
  try {
    const result = await api.confirmEmotionRecord(id, payload)
    if (!confirmed(result)) throw new Error('record_confirmation_incomplete')
    return result
  } catch (error) {
    const status = error.response?.status
    if (error.userMessage || (status && status !== 409 && status < 500)) throw error
    // A lost response or a state conflict is resolved by evidence from GET,
    // not by accepting the HTTP error itself or overwriting COMPLETE records.
    const latest = await api.getEmotionRecordDetail(id)
    if (confirmed(latest)) return latest
    throw error
  }
}
