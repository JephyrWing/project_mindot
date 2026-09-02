import httpClient from '../api/httpClient.js'

// 주간 리포트 API 호출 함수를 생성하는 팩토리 정의.
export const createReportsApi = (client) => ({
  // 로그인 사용자의 선택 주에 이미 생성된 주간 리포트 조회 처리.
  getWeeklyReport: async (weekStart) => {
    const { data } = await client.get('/api/reports/weekly', {
      params: { weekStart },
    })

    return data
  },

  // 선택 주의 최신 감정 기록과 CBT 결과를 사용한 리포트 생성 또는 갱신 처리.
  generateWeeklyReport: async (weekStart) => {
    const { data } = await client.post('/api/reports/weekly', null, {
      params: { weekStart },
    })

    return data
  },

  // 선택 주의 감정 기록과 CBT 결과를 상담용 PDF 파일로 생성하는 처리.
  exportWeeklyReportPdf: async ({ startDate, endDate }) => {
    const { data } = await client.post('/api/reports/export/pdf', {
      startDate,
      endDate,
      selectedDates: null,
      contentType: 'BOTH',
      includeFullCbtConversation: false,
    }, {
      responseType: 'blob',
    })

    return data
  },
})

// 공통 인증 HTTP 클라이언트를 사용하는 주간 리포트 API 함수 제공.
export const {
  getWeeklyReport,
  generateWeeklyReport,
  exportWeeklyReportPdf,
} = createReportsApi(httpClient)
