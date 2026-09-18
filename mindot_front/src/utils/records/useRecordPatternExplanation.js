import { useEffect, useRef, useState } from 'react'
import { getEmotionRecordPatternExplanation } from './recordsApi.js'

// One background request per saved version during this detail visit, including StrictMode.
export default function useRecordPatternExplanation(recordId, record, enabled) {
  const requests = useRef(new Map())
  const [result, setResult] = useState(null)
  const key = record?.completionStatus === 'COMPLETE'
    && !record.cbtCompleted
    && String(record.emotionRecordId) === String(recordId)
    ? JSON.stringify([recordId, record.rawText, record.situationText, record.automaticThought,
      record.primaryEmotionCode, record.primaryIntensity, record.contextCategory,
      record.timeBucket, record.occurredAt, record.secondaryEmotions, record.details]) : null

  useEffect(() => {
    if (!enabled || !key) return
    let active = true
    if (!requests.current.has(key)) {
      requests.current.set(key, getEmotionRecordPatternExplanation(recordId, { silentFailure: true })
        .then((value) => typeof value?.patternSummary === 'string'
          && value.patternSummary.trim() && value.similarCaseCount > 0 ? value : null)
        .catch(() => null))
    }
    requests.current.get(key).then((value) => {
      if (active) setResult({ key, value })
    })
    return () => { active = false }
  }, [enabled, key, recordId])

  // Hide cached/late results immediately after CBT completion or a record/version change.
  return enabled && result?.key === key ? result?.value : null
}
