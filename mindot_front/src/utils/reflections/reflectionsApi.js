import httpClient from '../api/httpClient.js'
export const newRequestKey = () => crypto.randomUUID()
const options = (key, revision) => ({
  timeout: 240000,
  headers: { 'Idempotency-Key': key, ...(revision == null ? {} : { 'If-Match': String(revision) }) },
})
export const createReflectionsApi = (client) => ({
  openReflection: async (target, key, revision) => (await client.post('/api/reflections/open', target, options(key, revision))).data,
  submitReflectionAnswer: async (id, answer, key, revision) => (await client.post(`/api/reflections/${id}/turn`, { answer }, options(key, revision))).data,
  retryReflection: async (id, key, revision) => (await client.post(`/api/reflections/${id}/retry`, null, options(key, revision))).data,
  confirmReflection: async (id, body, key, revision) => (await client.post(`/api/reflections/${id}/confirm`, body, options(key, revision))).data,
  cancelReflection: async (id, key, revision) => (await client.post(`/api/reflections/${id}/cancel`, null, options(key, revision))).data,
  getOpenReflectionSessions: async () => (await client.get('/api/reflections/open')).data,
  getReflectionSessionDetail: async (id) => (await client.get(`/api/reflections/${id}`)).data,
})
export const { openReflection, submitReflectionAnswer, retryReflection, confirmReflection, cancelReflection,
  getOpenReflectionSessions, getReflectionSessionDetail } = createReflectionsApi(httpClient)
