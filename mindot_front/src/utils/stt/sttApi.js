// 브라우저에서 녹음한 음성을 STT API에 전달하고 인식한 문장을 반환
import httpClient from '../api/httpClient.js'

export const transcribeAudio = async (audioBlob, filename) => {
  const formData = new FormData()
  formData.append('audio', audioBlob, filename)

  const { data } = await httpClient.post('/api/stt/transcribe', formData)
  return data.transcript
}
