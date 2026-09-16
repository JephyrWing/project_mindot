import httpClient from '../api/httpClient.js'

// 주간·월간 리포트 API 호출 함수를 생성하는 팩토리 정의.
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

  // 로그인 사용자의 선택 월에 이미 생성된 월간 리포트 조회 처리.
  getMonthlyReport: async (month) => {
    const { data } = await client.get('/api/reports/monthly', {
      params: { month },
    })

    return data
  },

  // 선택 월의 최신 감정 기록과 CBT 결과를 사용한 월간 리포트 생성 또는 갱신 처리.
  generateMonthlyReport: async (month) => {
    const { data } = await client.post('/api/reports/monthly', null, {
      params: { month },
    })

    return data
  },

  // 이미 생성된 선택 월 리포트를 시각화된 PDF 파일로 내려받는 처리.
  exportMonthlyReportPdf: async (month) => {
    const { data } = await client.get('/api/reports/monthly/pdf', {
      params: { month },
      responseType: 'blob',
    })

    return data
  },

  // 선택 기간 또는 개별 날짜의 지정 내용을 상담용 PDF 파일로 생성하는 처리.
  exportWeeklyReportPdf: async ({
    startDate,
    endDate,
    selectedDates,
    contentType,
    includeFullCbtConversation,
  }) => {
    const { data } = await client.post('/api/reports/export/pdf', {
      startDate,
      endDate,
      selectedDates,
      contentType,
      includeFullCbtConversation,
    }, {
      responseType: 'blob',
    })

    return data
  },
})

// 공통 인증 HTTP 클라이언트를 사용하는 리포트 API 함수 제공.
export const {
  getWeeklyReport,
  generateWeeklyReport,
  getMonthlyReport,
  generateMonthlyReport,
  exportMonthlyReportPdf,
  exportWeeklyReportPdf,
} = createReportsApi(httpClient)
