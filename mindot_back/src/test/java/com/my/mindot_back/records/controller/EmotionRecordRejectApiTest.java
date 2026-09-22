// 감정 기록 AI 제안 거절과 상세 조회 및 재분석 상태 초기화를 실제 DB로 검증
package com.my.mindot_back.records.controller;

import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.records.client.FastApiRecordAnalysisClient;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import com.my.mindot_back.records.entity.CompletionStatus;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.service.EmotionRecordSearchEmbeddingService;
import com.my.mindot_back.reports.service.ReportCacheInvalidationService;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.ConsentEvents;
import com.my.mindot_back.users.entity.ConsentType;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.ConsentEventsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmotionRecordRejectApiTest extends PostgresContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private ConsentEventsRepository consentEventsRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private FastApiRecordAnalysisClient fastApiRecordAnalysisClient;

    @MockitoBean
    private EmotionRecordSearchEmbeddingService searchEmbeddingService;

    @MockitoBean
    private ReportCacheInvalidationService reportCacheInvalidationService;

    private Users user;
    private Users otherUser;
    private String accessToken;
    private EmotionRecords partialRecord;

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(
                Users.create(
                        "reject-api@example.com",
                        "unused-password-hash",
                        "제안 거절 사용자"
                )
        );
        otherUser = usersRepository.saveAndFlush(
                Users.create(
                        "other-reject-api@example.com",
                        "unused-password-hash",
                        "다른 사용자"
                )
        );
        consentEventsRepository.saveAndFlush(
                ConsentEvents.grant(
                        user,
                        ConsentType.AI_ANALYSIS,
                        "ai-analysis-v1"
                )
        );

        partialRecord = createPartialRecord(
                user,
                "회의 중 실수해서 불안했다",
                Instant.parse("2026-09-21T01:00:00Z")
        );
        accessToken = jwtTokenProvider.createAccessToken(user.getId());
    }

    @Test
    void partialProposalCanBeRejectedAndDetailKeepsRejectedState()
            throws Exception {
        mockMvc.perform(
                        post(
                                "/api/records/{recordId}/reject",
                                partialRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emotionRecordId")
                        .value(partialRecord.getId()))
                .andExpect(jsonPath("$.rawText")
                        .value("회의 중 실수해서 불안했다"))
                .andExpect(jsonPath("$.completionStatus")
                        .value("QUICK"))
                .andExpect(jsonPath("$.analysisStatus")
                        .value("REJECTED"))
                .andExpect(jsonPath("$.situationText").isEmpty())
                .andExpect(jsonPath("$.automaticThought").isEmpty())
                .andExpect(jsonPath("$.primaryEmotionCode").isEmpty())
                .andExpect(jsonPath("$.primaryIntensity").isEmpty())
                .andExpect(jsonPath("$.secondaryEmotions").isEmpty())
                .andExpect(jsonPath("$.contextCategory").isEmpty())
                .andExpect(jsonPath("$.relatedPersonType").isEmpty())
                .andExpect(jsonPath("$.details").isEmpty());

        emotionRecordsRepository.flush();

        EmotionRecords saved = emotionRecordsRepository
                .findById(partialRecord.getId())
                .orElseThrow();

        assertThat(saved.getCompletionStatus())
                .isEqualTo(CompletionStatus.QUICK);
        assertThat(saved.getRawText())
                .isEqualTo("회의 중 실수해서 불안했다");
        assertThat(saved.getSituationText()).isNull();
        assertThat(saved.getAutomaticThought()).isNull();
        assertThat(saved.getPrimaryEmotionCode()).isNull();
        assertThat(saved.getPrimaryIntensity()).isNull();
        assertThat(saved.getSecondaryEmotions()).isEmpty();
        assertThat(saved.getContextCategory()).isNull();
        assertThat(saved.getRelatedPersonType()).isNull();
        assertThat(saved.getDetails()).isEmpty();
        assertThat(saved.isAiAnalysisRejected()).isTrue();

        mockMvc.perform(
                        get(
                                "/api/records/{recordId}",
                                partialRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completionStatus")
                        .value("QUICK"))
                .andExpect(jsonPath("$.analysisStatus")
                        .value("REJECTED"));
    }

    @Test
    void rejectedRecordCanBeReanalyzedAndRejectedStateIsCleared()
            throws Exception {
        mockMvc.perform(
                        post(
                                "/api/records/{recordId}/reject",
                                partialRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk());

        when(fastApiRecordAnalysisClient.analyze(partialRecord.getRawText()))
                .thenReturn(successfulAnalysis("다시 분석한 생각"));

        mockMvc.perform(
                        post(
                                "/api/records/{recordId}/reanalyze",
                                partialRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completionStatus")
                        .value("PARTIAL"))
                .andExpect(jsonPath("$.analysisStatus")
                        .value("COMPLETED"))
                .andExpect(jsonPath("$.automaticThought")
                        .value("다시 분석한 생각"));

        EmotionRecords saved = emotionRecordsRepository
                .findById(partialRecord.getId())
                .orElseThrow();

        assertThat(saved.isAiAnalysisRejected()).isFalse();
        assertThat(saved.getCompletionStatus())
                .isEqualTo(CompletionStatus.PARTIAL);
    }

    @Test
    void rejectEnforcesAuthenticationOwnershipAndState()
            throws Exception {
        EmotionRecords otherUsersRecord = createPartialRecord(
                otherUser,
                "다른 사용자의 감정 기록",
                Instant.parse("2026-09-21T02:00:00Z")
        );
        EmotionRecords completeRecord = createPartialRecord(
                user,
                "이미 확정한 감정 기록",
                Instant.parse("2026-09-21T03:00:00Z")
        );
        completeRecord.confirm(
                new EmotionRecordsConfirmRequestDto(
                        "회의에서 발표했다",
                        "실수해도 다시 설명할 수 있다",
                        "ANXIETY",
                        (short) 5,
                        List.of(),
                        "WORK",
                        "COLLEAGUE",
                        Map.of()
                )
        );
        emotionRecordsRepository.saveAndFlush(completeRecord);

        mockMvc.perform(
                        post(
                                "/api/records/{recordId}/reject",
                                partialRecord.getId()
                        )
                )
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        post(
                                "/api/records/{recordId}/reject",
                                otherUsersRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isNotFound());

        mockMvc.perform(
                        post(
                                "/api/records/{recordId}/reject",
                                completeRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isConflict());
    }

    private EmotionRecords createPartialRecord(
            Users owner,
            String rawText,
            Instant occurredAt
    ) {
        EmotionRecords record = EmotionRecords.createQuick(
                owner,
                rawText,
                InputType.TEXT,
                occurredAt
        );
        record.applyAiAnalysis(successfulAnalysis("나는 실수하면 안 된다"));
        return emotionRecordsRepository.saveAndFlush(record);
    }

    private FastApiRecordAnalysisResponseDto successfulAnalysis(
            String automaticThought
    ) {
        return new FastApiRecordAnalysisResponseDto(
                new FastApiRecordAnalysisResponseDto.StructuredRecord(
                        "회의에서 발표하던 상황",
                        "실수를 실패로 해석했다",
                        automaticThought,
                        List.of(
                                new FastApiRecordAnalysisResponseDto
                                        .EmotionItem("ANXIETY", 7)
                        ),
                        "가슴이 답답했다",
                        "말수가 줄었다",
                        "WORK",
                        "COLLEAGUE"
                ),
                null,
                new FastApiRecordAnalysisResponseDto.AnalysisMeta(
                        "test-model",
                        "analyze-record-v1"
                )
        );
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
