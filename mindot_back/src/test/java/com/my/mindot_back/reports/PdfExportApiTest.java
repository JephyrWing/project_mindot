// 상담용 PDF와 월간 PDF의 요청 범위·오류 응답·다운로드 계약을 실제 DB로 검증

package com.my.mindot_back.reports;

import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PdfExportApiTest
        extends PostgresContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Users user;
    private String accessToken;

    @Test
    void explicitDatesExcludeGapsAndOtherUsersAndPreserveMissingEmotions() throws Exception {
        emotionRecordsRepository.saveAndFlush(EmotionRecords.createQuick(user, "선택한날의미입력기록", InputType.TEXT, Instant.parse("2026-09-16T15:00:00Z")));
        emotionRecordsRepository.saveAndFlush(EmotionRecords.createQuick(user, "제외할중간날짜기록", InputType.TEXT, Instant.parse("2026-09-15T15:00:00Z")));
        var other = usersRepository.saveAndFlush(Users.create("other-pdf@example.invalid", "unused-password-hash", "별도 사용자"));
        emotionRecordsRepository.saveAndFlush(EmotionRecords.createQuick(other, "다른사용자의제외할기록", InputType.TEXT, Instant.parse("2026-09-16T15:00:00Z")));
        var result = mockMvc.perform(post("/api/reports/export/pdf")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"selectedDates":["2026-09-15","2026-09-17"],"contentType":"EMOTION_RECORDS","includeFullCbtConversation":false}
                        """))
                .andExpect(status().isOk()).andReturn();
        try (var doc = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertThat(text).contains("감정 기록 2건", "선택한날의미입력기록", "기록 #2")
                    .doesNotContain("제외할중간날짜기록", "다른사용자의제외할기록", "기록 #3");
            assertThat(java.util.regex.Pattern.compile("이 그래프 (\\d+)건").matcher(text).results()
                    .mapToInt(m -> Integer.parseInt(m.group(1))).sum()).isEqualTo(2);
        }
    }

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(
                Users.create(
                        "pdf-export@example.com",
                        "unused-password-hash",
                        "PDF 테스트 사용자"
                )
        );

        accessToken =
                jwtTokenProvider.createAccessToken(
                        user.getId()
                );

        EmotionRecords record =
                EmotionRecords.createQuick(
                        user,
                        "PDF에 포함할 감정 기록 원문",
                        InputType.TEXT,
                        Instant.parse(
                                "2026-09-15T03:00:00Z"
                        )
                );

        record.confirm(
                new EmotionRecordsConfirmRequestDto(
                        "PDF 테스트 상황",
                        "PDF 테스트 자동 사고",
                        "ANXIETY",
                        (short) 7,
                        List.of(),
                        "WORK",
                        "OTHER",
                        Map.of()
                )
        );

        emotionRecordsRepository.saveAndFlush(
                record
        );
    }

    @Test
    void consultationPdfSupportsPeriodAndSelectedDates()
            throws Exception {
        MvcResult periodResult =
                mockMvc.perform(
                                post("/api/reports/export/pdf")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                bearer(accessToken)
                                        )
                                        .contentType(
                                                MediaType.APPLICATION_JSON
                                        )
                                        .content(
                                                """
                                                {
                                                  "startDate": "2026-09-01",
                                                  "endDate": "2026-10-01",
                                                  "selectedDates": null,
                                                  "contentType": "BOTH",
                                                  "includeFullCbtConversation": false
                                                }
                                                """
                                        )
                        )
                        .andExpect(status().isOk())
                        .andExpect(
                                content().contentType(
                                        MediaType.APPLICATION_PDF
                                )
                        )
                        .andExpect(
                                header().string(
                                        HttpHeaders.CONTENT_DISPOSITION,
                                        "attachment; filename=\"mindot-consultation-record.pdf\""
                                )
                        )
                        .andReturn();

        assertValidPdf(
                periodResult,
                1
        );

        MvcResult selectedDatesResult =
                mockMvc.perform(
                                post("/api/reports/export/pdf")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                bearer(accessToken)
                                        )
                                        .contentType(
                                                MediaType.APPLICATION_JSON
                                        )
                                        .content(
                                                """
                                                {
                                                  "startDate": null,
                                                  "endDate": null,
                                                  "selectedDates": [
                                                    "2026-09-15"
                                                  ],
                                                  "contentType": "EMOTION_RECORDS",
                                                  "includeFullCbtConversation": false
                                                }
                                                """
                                        )
                        )
                        .andExpect(status().isOk())
                        .andExpect(
                                content().contentType(
                                        MediaType.APPLICATION_PDF
                                )
                        )
                        .andReturn();

        assertValidPdf(
                selectedDatesResult,
                1
        );
    }

    @Test
    void consultationPdfRejectsInvalidRangeAndMissingData()
            throws Exception {
        mockMvc.perform(
                        post("/api/reports/export/pdf")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "startDate": "2026-09-01",
                                          "endDate": "2026-10-02",
                                          "selectedDates": null,
                                          "contentType": "BOTH",
                                          "includeFullCbtConversation": false
                                        }
                                        """
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.status")
                                .value(400)
                );

        mockMvc.perform(
                        post("/api/reports/export/pdf")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "startDate": "2026-09-15",
                                          "endDate": "2026-09-15",
                                          "selectedDates": [
                                            "2026-09-15"
                                          ],
                                          "contentType": "BOTH",
                                          "includeFullCbtConversation": false
                                        }
                                        """
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.status")
                                .value(400)
                );

        mockMvc.perform(
                        post("/api/reports/export/pdf")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "startDate": null,
                                          "endDate": null,
                                          "selectedDates": [
                                            "2026-08-01"
                                          ],
                                          "contentType": "EMOTION_RECORDS",
                                          "includeFullCbtConversation": false
                                        }
                                        """
                                )
                )
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.status")
                                .value(409)
                );
    }

    @Test
    void monthlyPdfRequiresGeneratedReport()
            throws Exception {
        mockMvc.perform(
                        get("/api/reports/monthly/pdf")
                                .param("month", "2026-09")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.status")
                                .value(404)
                );

        mockMvc.perform(
                        post("/api/reports/monthly")
                                .param("month", "2026-09")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.recordCount")
                                .value(1)
                );

        MvcResult monthlyPdfResult =
                mockMvc.perform(
                                get("/api/reports/monthly/pdf")
                                        .param(
                                                "month",
                                                "2026-09"
                                        )
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                bearer(accessToken)
                                        )
                        )
                        .andExpect(status().isOk())
                        .andExpect(
                                content().contentType(
                                        MediaType.APPLICATION_PDF
                                )
                        )
                        .andExpect(
                                header().string(
                                        HttpHeaders.CONTENT_DISPOSITION,
                                        "attachment; filename=\"mindot-monthly-report-2026-09.pdf\""
                                )
                        )
                        .andExpect(
                                header().exists(
                                        HttpHeaders.CONTENT_LENGTH
                                )
                        )
                        .andReturn();

        assertValidPdf(
                monthlyPdfResult,
                2
        );
    }

    private void assertValidPdf(
            MvcResult result,
            int minimumPageCount
    ) throws Exception {
        byte[] pdfBytes =
                result.getResponse()
                        .getContentAsByteArray();

        assertThat(pdfBytes)
                .isNotEmpty();

        try (
                PDDocument document =
                        Loader.loadPDF(pdfBytes)
        ) {
            assertThat(document.getNumberOfPages())
                    .isGreaterThanOrEqualTo(
                            minimumPageCount
                    );
        }
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
