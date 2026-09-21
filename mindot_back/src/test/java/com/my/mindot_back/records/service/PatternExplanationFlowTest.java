// 패턴 설명에 확정된 AFTER 결과만 사용하고 실제 사용 건수를 반환하는지 검증

package com.my.mindot_back.records.service;

import com.my.mindot_back.common.rag.RagUtils;
import com.my.mindot_back.records.client.FastApiPatternExplanationClient;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.dto.ai.FastApiPatternExplanationRequestDto;
import com.my.mindot_back.records.dto.ai.FastApiPatternExplanationResponseDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.records.repository.SessionDistortionsRepository;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.service.ConsentEventsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatternExplanationFlowTest {

    private static final Long USER_ID = 7L;
    private static final Long RECORD_ID = 1L;

    @Mock
    private EmotionRecordsRepository emotionRecordsRepository;

    @Mock
    private ReflectionSessionsRepository reflectionSessionsRepository;

    @Mock
    private SessionDistortionsRepository sessionDistortionsRepository;

    @Mock
    private ConsentEventsService consentEventsService;

    @Mock
    private RagUtils ragUtils;

    @Mock
    private FastApiPatternExplanationClient
            fastApiPatternExplanationClient;

    @InjectMocks
    private EmotionRecordsService emotionRecordsService;

    private EmotionRecords currentRecord;

    @BeforeEach
    void setUp() {
        Users user = org.mockito.Mockito.mock(Users.class);

        currentRecord = EmotionRecords.createQuick(
                user,
                "현재 감정 기록",
                InputType.TEXT,
                Instant.parse("2026-09-21T01:00:00Z")
        );

        ReflectionTestUtils.setField(
                currentRecord,
                "id",
                RECORD_ID
        );

        currentRecord.confirm(
                new EmotionRecordsConfirmRequestDto(
                        "회의에서 의견이 받아들여지지 않았다",
                        "내 의견은 항상 중요하지 않다",
                        "SADNESS",
                        (short) 7,
                        List.of(),
                        "WORK",
                        "COLLEAGUE",
                        Map.of()
                )
        );

        when(
                emotionRecordsRepository.findByIdAndUser_Id(
                        RECORD_ID,
                        USER_ID
                )
        ).thenReturn(Optional.of(currentRecord));

        when(
                reflectionSessionsRepository
                        .countByUser_IdAndStatusAndUserConfirmedTrueAndEmotionRecord_IdNot(
                                USER_ID,
                                ReflectionSessionStatus.COMPLETED,
                                RECORD_ID
                        )
        ).thenReturn(10L);
    }

    @Test
    void noEligibleConfirmedAfterCaseReturnsConflict() {
        ReflectionSessions unconfirmed =
                insightSession(
                        11L,
                        false,
                        "확인되지 않은 새로운 생각"
                );

        ReflectionSessions blankAfter =
                insightSession(
                        12L,
                        true,
                        "   "
                );

        when(ragUtils.searchSimilarCases(currentRecord))
                .thenReturn(List.of(
                        unconfirmed,
                        blankAfter
                ));

        assertThatThrownBy(
                () -> emotionRecordsService.explainPattern(
                        USER_ID,
                        RECORD_ID
                )
        )
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(fastApiPatternExplanationClient);
    }

    @Test
    void onlyConfirmedAfterCasesAreSentAndCountIsExact() {
        ReflectionSessions eligible =
                insightSession(
                        21L,
                        true,
                        "한 번의 반응만으로 내 능력을 단정하지 말자"
                );

        ReflectionSessions unconfirmed =
                insightSession(
                        22L,
                        false,
                        "확정되지 않은 생각"
                );

        ReflectionSessions blankAfter =
                insightSession(
                        23L,
                        true,
                        ""
                );

        when(ragUtils.searchSimilarCases(currentRecord))
                .thenReturn(List.of(
                        eligible,
                        unconfirmed,
                        blankAfter
                ));

        when(
                fastApiPatternExplanationClient.explain(any())
        ).thenReturn(
                new FastApiPatternExplanationResponseDto(
                        "충분한 근거 없이 자신을 부정적으로 해석하는 흐름",
                        List.of("JUMPING_TO_CONCLUSIONS"),
                        "한 번의 반응만으로 내 능력을 단정하지 말자",
                        "사실과 해석을 구분해 보세요"
                )
        );

        var response =
                emotionRecordsService.explainPattern(
                        USER_ID,
                        RECORD_ID
                );

        assertThat(response.emotionRecordId())
                .isEqualTo(RECORD_ID);
        assertThat(response.similarCaseCount())
                .isEqualTo(1);
        assertThat(response.patternSummary())
                .isEqualTo(
                        "충분한 근거 없이 자신을 부정적으로 해석하는 흐름"
                );

        ArgumentCaptor<FastApiPatternExplanationRequestDto>
                requestCaptor =
                ArgumentCaptor.forClass(
                        FastApiPatternExplanationRequestDto.class
                );

        verify(fastApiPatternExplanationClient)
                .explain(requestCaptor.capture());

        FastApiPatternExplanationRequestDto request =
                requestCaptor.getValue();

        assertThat(request.emotionRecordId())
                .isEqualTo(RECORD_ID);
        assertThat(request.similarCases())
                .hasSize(1);
        assertThat(
                request.similarCases()
                        .get(0)
                        .reflectionSessionId()
        ).isEqualTo(21L);
        assertThat(
                request.similarCases()
                        .get(0)
                        .confirmedResult()
                        .get("userConfirmed")
        ).isEqualTo(true);
        assertThat(
                request.similarCases()
                        .get(0)
                        .confirmedResult()
                        .get("afterText")
        ).isEqualTo(
                "한 번의 반응만으로 내 능력을 단정하지 말자"
        );

        assertThat(request.similarCases())
                .extracting(item -> item.reflectionSessionId())
                .doesNotContain(22L, 23L);
    }

    private ReflectionSessions insightSession(
            Long sessionId,
            boolean userConfirmed,
            String afterText
    ) {
        ReflectionSessions session =
                org.mockito.Mockito.mock(
                        ReflectionSessions.class
                );

        Map<String, Object> confirmedResult = Map.of(
                "userConfirmed",
                userConfirmed,
                "afterText",
                afterText
        );

        when(session.getId()).thenReturn(sessionId);
        when(session.getEmotionRecord())
                .thenReturn(currentRecord);
        when(session.getHelpfulnessScore())
                .thenReturn((short) 4);
        when(session.confirmedBeforeText())
                .thenReturn("나는 항상 무시당한다");
        when(session.getAlternativeThoughtText())
                .thenReturn(afterText);
        when(session.confirmedInsight())
                .thenReturn(confirmedResult);
        when(session.confirmedInsightCodes())
                .thenReturn(
                        List.of("JUMPING_TO_CONCLUSIONS")
                );

        return session;
    }
}