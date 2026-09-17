// 브라우저 마이크로 녹음하고 실제 음성 형식과 데이터를 보관하는 훅
import { useEffect, useRef, useState } from 'react'

const MAX_RECORDING_MS = 50_000
const preferredTypes = [
  'audio/webm;codecs=opus',
  'audio/ogg;codecs=opus',
]

export default function useVoiceRecorder() {
  const recorderRef = useRef(null)
  const streamRef = useRef(null)
  const timerRef = useRef(null)
  const requestingRef = useRef(false)
  const mountedRef = useRef(true)

  const [recordingState, setRecordingState] = useState('idle')
  const [recordedAudio, setRecordedAudio] = useState(null)
  const [recordingError, setRecordingError] = useState('')

  useEffect(() => {
    mountedRef.current = true

    return () => {
      mountedRef.current = false
      clearTimeout(timerRef.current)

      const recorder = recorderRef.current
      if (recorder) {
        recorder.ondataavailable = null
        recorder.onstop = null
        recorder.onerror = null
        if (recorder.state !== 'inactive') recorder.stop()
      }

      streamRef.current?.getTracks().forEach((track) => track.stop())
    }
  }, [])

  const startRecording = async () => {
    if (requestingRef.current || recorderRef.current?.state === 'recording') return

    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      setRecordingError('이 브라우저에서는 마이크 녹음을 사용할 수 없습니다.')
      return
    }

    requestingRef.current = true
    setRecordingState('requesting')
    setRecordingError('')
    setRecordedAudio(null)

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })

      if (!mountedRef.current) {
        stream.getTracks().forEach((track) => track.stop())
        return
      }

      streamRef.current = stream
      const supportedType = preferredTypes.find((type) =>
        MediaRecorder.isTypeSupported(type)
      )

      if (!supportedType) {
        stream.getTracks().forEach((track) => track.stop())
        streamRef.current = null
        setRecordingError('이 브라우저에서는 지원하는 음성 녹음 형식이 없습니다.')
        setRecordingState('idle')
        return
      }

      const recorder = new MediaRecorder(stream, { mimeType: supportedType })
      recorderRef.current = recorder

      const chunks = []
      let failed = false

      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunks.push(event.data)
      }

      recorder.onerror = () => {
        failed = true
        setRecordingError('녹음 중 오류가 발생했습니다. 다시 시도해 주세요.')
      }

      recorder.onstop = () => {
        clearTimeout(timerRef.current)
        stream.getTracks().forEach((track) => track.stop())
        streamRef.current = null

        if (failed) {
          setRecordingState('idle')
          return
        }

        const mimeType = recorder.mimeType || chunks[0]?.type || ''
        const blob = new Blob(chunks, { type: mimeType })

        if (blob.size === 0) {
          setRecordingError('녹음된 음성이 없습니다. 다시 시도해 주세요.')
          setRecordingState('idle')
          return
        }

        setRecordedAudio({ blob, mimeType, bytes: blob.size })
        setRecordingState('ready')
      }

      recorder.start()
      setRecordingState('recording')
      timerRef.current = setTimeout(() => {
        if (recorder.state === 'recording') recorder.stop()
      }, MAX_RECORDING_MS)
    } catch (error) {
      streamRef.current?.getTracks().forEach((track) => track.stop())
      streamRef.current = null
      setRecordingError(
        error.name === 'NotAllowedError'
          ? '마이크 사용을 허용해 주세요.'
          : '마이크를 시작할 수 없습니다.',
      )
      setRecordingState('idle')
    } finally {
      requestingRef.current = false
    }
  }

  const stopRecording = () => {
    if (recorderRef.current?.state === 'recording') {
      setRecordingState('stopping')
      recorderRef.current.stop()
    }
  }

  // 새 감정 기록을 시작할 때 이전 녹음 데이터와 오류 표시를 지운다
  // 녹음이 진행 중이거나 마이크 권한을 요청 중이면 초기화하지 않는다
  const resetRecording = () => {
    if (
      requestingRef.current
      || (recorderRef.current && recorderRef.current.state !== 'inactive')
    ) return

    clearTimeout(timerRef.current)
    recorderRef.current = null
   setRecordedAudio(null)
    setRecordingError('')
    setRecordingState('idle')
  }

  return {
    recordingState,
    recordedAudio,
    recordingError,
    startRecording,
    stopRecording,
    resetRecording,
  }
}