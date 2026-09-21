// 감정 기록 누락정보 보완질문의 판정 순서와 상태 및 소유권 처리를 실제 DB로 검증
package com.my.mindot_back.records.controller;

import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import com.my.mindot_back.records.entity.CompletionStatus;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmotionRecordMissingQuestionsApiTest
        extends PostgresContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private AiJobsRepository aiJobsRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Users user;
    private Users otherUser;
    private String accessToken;

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(
                Users.create(
                        "missing-questions@example.com",
                        "unused-password-hash",
                        "보완 질문 사용자"
                )
        );
        otherUser = usersRepository.saveAndFlush(
                Users.create(
                        "other-missing-questions@example.com",
                        "unused-password-hash",
                        "다른 사용자"
                )
        );
        accessToken = jwtTokenProvider.createAccessToken(user.getId());
    }

    @Test
    void partialRecordReturnsOnlyMissingQuestionsInDocumentOrder()
            throws Exception {
        EmotionRecords partialRecord = createPartialRecord(
                user,
                "누락 질문을 확인할 기록",
                Instant.parse("2026-09-21T04:00:00Z"),
                missingFieldAnalysis()
        );
        int recordCountBefore = (int) emotionRecordsRepository.count();
        int aiJobCountBefore = (int) aiJobsRepository.count();
        Instant updatedAtBefore = partialRecord.getUpdatedAt();

        mockMvc.perform(
                        get(
                                "/api/records/{recordId}/questions/missing",
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
                .andExpect(jsonPath("$.completionStatus")
                        .value("PARTIAL"))
                .andExpect(jsonPath("$.questions.length()")
                        .value(5))
                .andExpect(jsonPath("$.questions[0].fieldName")
                        .value("situationText"))
                .andExpect(jsonPath("$.questions[0].required")
                        .value(false))
                .andExpect(jsonPath("$.questions[1].fieldName")
                        .value("automaticThought"))
                .andExpect(jsonPath("$.questions[2].fieldName")
                        .value("primaryEmotionCode"))
                .andExpect(jsonPath("$.questions[2].required")
                        .value(true))
                .andExpect(jsonPath("$.questions[3].fieldName")
                        .value("contextCategory"))
                .andExpect(jsonPath("$.questions[4].fieldName")
                        .value("bodyReaction"))
                .andExpect(jsonPath("$.questions[*].fieldName")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.hasItem(
                                        "primaryIntensity"
                                )
                        )))
                .andExpect(jsonPath("$.questions[*].fieldName")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.hasItem(
                                        "relatedPersonType"
                                )
                        )))
                .andExpect(jsonPath("$.questions[*].fieldName")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.hasItem("behavior")
                        )));

        EmotionRecords after = emotionRecordsRepository
                .findById(partialRecord.getId())
                .orElseThrow();

        assertThat(emotionRecordsRepository.count())
                .isEqualTo(recordCountBefore);
        assertThat(aiJobsRepository.count())
                .isEqualTo(aiJobCountBefore);
        assertThat(after.getCompletionStatus())
                .isEqualTo(CompletionStatus.PARTIAL);
        assertThat(after.getUpdatedAt()).isEqualTo(updatedAtBefore);
    }

    @Test
    void completeReturnsEmptyQuestionsAndQuickReturnsConflict()
            throws Exception {
        EmotionRecords completeRecord = createPartialRecord(
                user,
                "확정 완료한 기록",
                Instant.parse("2026-09-21T05:00:00Z"),
                completeAnalysis()
        );
        completeRecord.confirm(
                new EmotionRecordsConfirmRequestDto(
                        "친구와 대화했다",
                        "내 말을 오해할 수 있다",
                        "ANXIETY",
                        (short) 0,
                        List.of(),
                        "RELATIONSHIP",
                        "FRIEND",
                        Map.of(
                                "bodyReaction", "없음",
                                "behavior", "차분하게 설명했다"
                        )
                )
        );
        emotionRecordsRepository.saveAndFlush(completeRecord);

        EmotionRecords quickRecord = emotionRecordsRepository.saveAndFlush(
                EmotionRecords.createQuick(
                        user,
                        "아직 분석 전인 기록",
                        InputType.TEXT,
                        Instant.parse("2026-09-21T06:00:00Z")
                )
        );

        mockMvc.perform(
                        get(
                                "/api/records/{recordId}/questions/missing",
                                completeRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completionStatus")
                        .value("COMPLETE"))
                .andExpect(jsonPath("$.questions").isEmpty());

        mockMvc.perform(
                        get(
                                "/api/records/{recordId}/questions/missing",
                                quickRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isConflict());
    }

    @Test
    void missingQuestionsEnforcesAuthenticationAndOwnership()
            throws Exception {
        EmotionRecords otherUsersRecord = createPartialRecord(
                otherUser,
                "다른 사용자의 기록",
                Instant.parse("2026-09-21T07:00:00Z"),
                completeAnalysis()
        );

        mockMvc.perform(
                        get(
                                "/api/records/{recordId}/questions/missing",
                                otherUsersRecord.getId()
                        )
                )
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        get(
                                "/api/records/{recordId}/questions/missing",
                                otherUsersRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isNotFound());
    }

    private EmotionRecords createPartialRecord(
            Users owner,
            String rawText,
            Instant occurredAt,
            FastApiRecordAnalysisResponseDto analysis
    ) {
        EmotionRecords record = EmotionRecords.createQuick(
                owner,
                rawText,
                InputType.TEXT,
                occurredAt
        );
        record.applyAiAnalysis(analysis);
        return emotionRecordsRepository.saveAndFlush(record);
    }

    private FastApiRecordAnalysisResponseDto missingFieldAnalysis() {
        return new FastApiRecordAnalysisResponseDto(
                new FastApiRecordAnalysisResponseDto.StructuredRecord(
                        "   ",
                        "상황을 부정적으로 해석했다",
                        null,
                        List.of(
                                new FastApiRecordAnalysisResponseDto
                                        .EmotionItem(" ", 0)
                        ),
                        " ",
                        "숨을 골랐다",
                        null,
                        "COLLEAGUE"
                ),
                null,
                new FastApiRecordAnalysisResponseDto.AnalysisMeta(
                        "test-model",
                        "analyze-record-v1"
                )
        );
    }

    private FastApiRecordAnalysisResponseDto completeAnalysis() {
        return new FastApiRecordAnalysisResponseDto(
                new FastApiRecordAnalysisResponseDto.StructuredRecord(
                        "친구와 대화하던 상황",
                        "오해받을 수 있다고 해석했다",
                        "내 말을 오해할 수 있다",
                        List.of(
                                new FastApiRecordAnalysisResponseDto
                                        .EmotionItem("ANXIETY", 0)
                        ),
                        "특별한 반응은 없었다",
                        "차분하게 설명했다",
                        "RELATIONSHIP",
                        "FRIEND"
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
