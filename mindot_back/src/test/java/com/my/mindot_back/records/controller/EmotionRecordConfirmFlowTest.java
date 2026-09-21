// 감정 기록 확정·검증 오류·소유권·수정 후 조회와 리포트 무효화를 실제 DB로 검증

package com.my.mindot_back.records.controller;

import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import com.my.mindot_back.records.entity.CompletionStatus;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.reports.service.ReportCacheInvalidationService;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmotionRecordConfirmFlowTest
        extends PostgresContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ReportCacheInvalidationService
            reportCacheInvalidationService;

    private Users user;
    private Users otherUser;
    private String accessToken;

    private EmotionRecords partialRecord;
    private EmotionRecords quickRecord;
    private EmotionRecords editableRecord;
    private EmotionRecords otherUsersRecord;

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(
                Users.create(
                        "confirm-flow@example.com",
                        "unused-password-hash",
                        "확정 흐름 사용자"
                )
        );

        otherUser = usersRepository.saveAndFlush(
                Users.create(
                        "other-confirm-flow@example.com",
                        "unused-password-hash",
                        "다른 확정 사용자"
                )
        );

        partialRecord = createPartialRecord(
                user,
                "확정할 감정 기록",
                Instant.parse("2026-09-20T01:00:00Z")
        );

        quickRecord = emotionRecordsRepository.saveAndFlush(
                EmotionRecords.createQuick(
                        user,
                        "아직 분석되지 않은 기록",
                        InputType.TEXT,
                        Instant.parse("2026-09-20T02:00:00Z")
                )
        );

        editableRecord = createCompleteRecord(
                user,
                "수정할 완료 기록",
                Instant.parse("2026-09-18T01:00:00Z")
        );

        otherUsersRecord = createPartialRecord(
                otherUser,
                "다른 사용자의 기록",
                Instant.parse("2026-09-20T03:00:00Z")
        );

        accessToken =
                jwtTokenProvider.createAccessToken(user.getId());
    }

    @Test
    void partialRecordCanBeConfirmedWithUserValues()
            throws Exception {
        mockMvc.perform(
                        post(
                                "/api/records/{recordId}/confirm",
                                partialRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "situationText": "회의에서 발표했다",
                                          "automaticThought": "실수해도 다시 설명할 수 있다",
                                          "primaryEmotionCode": "불안",
                                          "primaryIntensity": 6,
                                          "secondaryEmotions": [],
                                          "contextCategory": "WORK",
                                          "relatedPersonType": "COLLEAGUE",
                                          "details": {
                                            "behavior": "천천히 다시 설명했다"
                                          }
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.completionStatus")
                                .value("COMPLETE")
                )
                .andExpect(
                        jsonPath("$.situationText")
                                .value("회의에서 발표했다")
                )
                .andExpect(
                        jsonPath("$.automaticThought")
                                .value(
                                        "실수해도 다시 설명할 수 있다"
                                )
                )
                .andExpect(
                        jsonPath("$.primaryEmotionCode")
                                .value("ANXIETY")
                )
                .andExpect(
                        jsonPath("$.primaryIntensity")
                                .value(6)
                );

        EmotionRecords saved = emotionRecordsRepository
                .findById(partialRecord.getId())
                .orElseThrow();

        assertThat(saved.getCompletionStatus())
                .isEqualTo(CompletionStatus.COMPLETE);
        assertThat(saved.getAutomaticThought())
                .isEqualTo("실수해도 다시 설명할 수 있다");

        verify(
                reportCacheInvalidationService
        ).invalidateByOccurredAt(
                user.getId(),
                partialRecord.getOccurredAt()
        );
    }

    @Test
    void invalidValuesWrongStateAndOtherOwnerReturnExpectedErrors()
            throws Exception {
        mockMvc.perform(
                        post(
                                "/api/records/{recordId}/confirm",
                                partialRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        confirmJson(
                                                "__CUSTOM_EMOTION__",
                                                5
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(
                        post(
                                "/api/records/{recordId}/confirm",
                                partialRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        confirmJson(
                                                "ANXIETY",
                                                11
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(
                        post(
                                "/api/records/{recordId}/confirm",
                                quickRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        confirmJson(
                                                "ANXIETY",
                                                5
                                        )
                                )
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(
                        post(
                                "/api/records/{recordId}/confirm",
                                otherUsersRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        confirmJson(
                                                "ANXIETY",
                                                5
                                        )
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        EmotionRecords unchanged = emotionRecordsRepository
                .findById(partialRecord.getId())
                .orElseThrow();

        assertThat(unchanged.getCompletionStatus())
                .isEqualTo(CompletionStatus.PARTIAL);
    }

    @Test
    void updateChangesTimeAndAnalysisAndLaterQueriesUseNewValues()
            throws Exception {
        Instant previousOccurredAt =
                editableRecord.getOccurredAt();
        Instant newOccurredAt =
                Instant.parse("2026-09-21T05:00:00Z");

        mockMvc.perform(
                        patch(
                                "/api/records/{recordId}",
                                editableRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "occurredAt": "%s",
                                          "analysis": {
                                            "situationText": "가족과 산책했다",
                                            "automaticThought": "함께하니 마음이 편안하다",
                                            "primaryEmotionCode": "기쁨",
                                            "primaryIntensity": 3,
                                            "secondaryEmotions": [],
                                            "contextCategory": "FAMILY",
                                            "relatedPersonType": "FAMILY",
                                            "details": {
                                              "behavior": "대화를 나눴다"
                                            }
                                          }
                                        }
                                        """.formatted(newOccurredAt))
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.occurredAt")
                                .value(newOccurredAt.toString())
                )
                .andExpect(
                        jsonPath("$.situationText")
                                .value("가족과 산책했다")
                )
                .andExpect(
                        jsonPath("$.automaticThought")
                                .value(
                                        "함께하니 마음이 편안하다"
                                )
                )
                .andExpect(
                        jsonPath("$.primaryEmotionCode")
                                .value("JOY")
                )
                .andExpect(
                        jsonPath("$.primaryIntensity")
                                .value(3)
                )
                .andExpect(
                        jsonPath("$.contextCategory")
                                .value("FAMILY")
                );

        mockMvc.perform(
                        get("/api/records")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .param("period", "ALL")
                                .param("emotionCode", "JOY")
                                .param(
                                        "contextCategory",
                                        "FAMILY"
                                )
                                .param("sort", "LATEST")
                                .param("page", "0")
                                .param("size", "10")
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(1)
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].emotionRecordId"
                        ).value(
                                editableRecord.getId()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].primaryEmotionCode"
                        ).value("JOY")
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].primaryIntensity"
                        ).value(3)
                );

        ArgumentCaptor<Instant[]> occurredAts =
                ArgumentCaptor.forClass(Instant[].class);

        verify(
                reportCacheInvalidationService
        ).invalidateByOccurredAt(
                eq(user.getId()),
                occurredAts.capture()
        );

        assertThat(occurredAts.getValue())
                .containsExactly(
                        previousOccurredAt,
                        newOccurredAt
                );
    }

    private EmotionRecords createPartialRecord(
            Users owner,
            String rawText,
            Instant occurredAt
    ) {
        EmotionRecords record =
                EmotionRecords.createQuick(
                        owner,
                        rawText,
                        InputType.TEXT,
                        occurredAt
                );

        record.applyAiAnalysis(
                new FastApiRecordAnalysisResponseDto(
                        new FastApiRecordAnalysisResponseDto
                                .StructuredRecord(
                                "기존 상황",
                                "기존 해석",
                                "기존 자동적 사고",
                                List.of(
                                        new FastApiRecordAnalysisResponseDto
                                                .EmotionItem(
                                                "ANXIETY",
                                                7
                                        )
                                ),
                                "가슴이 답답하다",
                                "말을 줄였다",
                                "WORK",
                                "COLLEAGUE"
                        ),
                        null,
                        new FastApiRecordAnalysisResponseDto
                                .AnalysisMeta(
                                "test-model",
                                "analyze-record-v1"
                        )
                )
        );

        return emotionRecordsRepository.saveAndFlush(record);
    }

    private EmotionRecords createCompleteRecord(
            Users owner,
            String rawText,
            Instant occurredAt
    ) {
        EmotionRecords record =
                EmotionRecords.createQuick(
                        owner,
                        rawText,
                        InputType.TEXT,
                        occurredAt
                );

        record.confirm(
                new EmotionRecordsConfirmRequestDto(
                        "기존 완료 상황",
                        "기존 완료 생각",
                        "SADNESS",
                        (short) 5,
                        List.of(),
                        "WORK",
                        "COLLEAGUE",
                        Map.of()
                )
        );

        return emotionRecordsRepository.saveAndFlush(record);
    }

    private String confirmJson(
            String emotionCode,
            int intensity
    ) {
        return """
                {
                  "situationText": "검증 상황",
                  "automaticThought": "검증 생각",
                  "primaryEmotionCode": "%s",
                  "primaryIntensity": %d,
                  "secondaryEmotions": [],
                  "contextCategory": "WORK",
                  "relatedPersonType": "COLLEAGUE",
                  "details": {}
                }
                """.formatted(emotionCode, intensity);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}