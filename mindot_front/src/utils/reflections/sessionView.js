// Retries share inputRevision, so a late poll must also respect job ordering.
export function acceptSessionView(current, next) {
  if (!current || current.sessionId !== next.sessionId) return true
  if (current.revision !== next.revision) return next.revision > current.revision
  const oldJob = current.job?.jobId ?? 0
  const newJob = next.job?.jobId ?? 0
  if (oldJob !== newJob) return newJob > oldJob
  const rank = { PENDING: 0, PROCESSING: 1, FAILED: 2, COMPLETED: 2 }
  return (rank[next.job?.status] ?? 0) >= (rank[current.job?.status] ?? 0)
}
