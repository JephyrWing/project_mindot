// 주간 리포트의 기간 경계·감정 통계·CBT 포함 조건·빈 기간 처리를 실제 DB로 검증

package com.my.mindot_back.reports;

import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.dto.ReflectionSessionConfirmRequestDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.reports.dto.WeeklyReportResponseDto;
import com.my.mindot_back.reports.service.WeeklyReportsService;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class WeeklyReportIntegrationTest
        extends PostgresContainerTestBase {

    private static final LocalDate WEEK_START =
            LocalDate.of(2026, 9, 14);

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private ReflectionSessionsRepository
            reflectionSessionsRepository;

    @Autowired
    private WeeklyReportsService weeklyReportsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private Users user;

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(
                Users.create(
                        "weekly-report@example.com",
                        "unused-password-hash",
                        "주간 리포트 사용자"
                )
        );
    }

    @Test
    void weeklyReportUsesBoundariesAndOnlyConfirmedCompletedCbt() {
        createRecord(
                "주간 시작 직전 기록",
                "SADNESS",
                (short) 10,
                Instant.parse("2026-09-13T14:59:59Z")
        );

        createRecord(
                "월요일 시작 경계 기록",
                "SADNESS",
                (short) 8,
                Instant.parse("2026-09-13T15:00:00Z")
        );

        createRecord(
                "일요일 종료 직전 기록",
                "JOY",
                (short) 4,
                Instant.parse("2026-09-20T14:59:59Z")
        );

        createRecord(
                "다음 주 시작 경계 기록",
                "ANXIETY",
                (short) 9,
                Instant.parse("2026-09-20T15:00:00Z")
        );

        ReflectionSessions legacySession =
                createLegacyCompletedSession(
                        createRecord(
                                "구형 CBT 연결 기록",
                                "ANXIETY",
                                (short) 7,
                                Instant.parse(
                                        "2026-09-10T03:00:00Z"
                                )
                        ),
                        (short) 4
                );

        ReflectionSessions q14Session =
                createQ14CompletedSession(
                        createRecord(
                                "Q14 CBT 연결 기록",
                                "ANGER",
                                (short) 6,
                                Instant.parse(
                                        "2026-09-22T03:00:00Z"
                                )
                        ),
                        (short) 2
                );

        ReflectionSessions outsideCompletedSession =
                createLegacyCompletedSession(
                        createRecord(
                                "기간 밖 완료 CBT 연결 기록",
                                "FEAR",
                                (short) 5,
                                Instant.parse(
                                        "2026-09-11T03:00:00Z"
                                )
                        ),
                        (short) 5
                );

        ReflectionSessions openSession =
                ReflectionSessions.create(
                        user,
                        createRecord(
                                "진행 중 CBT 연결 기록",
                                "SADNESS",
                                (short) 5,
                                Instant.parse(
                                        "2026-09-12T03:00:00Z"
                                )
                        )
                );

        reflectionSessionsRepository.saveAndFlush(
                openSession
        );

        ReflectionSessions cancelledSession =
                ReflectionSessions.create(
                        user,
                        createRecord(
                                "취소 CBT 연결 기록",
                                "SADNESS",
                                (short) 5,
                                Instant.parse(
                                        "2026-09-12T04:00:00Z"
                                )
                        )
                );

        cancelledSession.cancel();

        reflectionSessionsRepository.saveAndFlush(
                cancelledSession
        );

        setCompletedAt(
                legacySession.getId(),
                Instant.parse("2026-09-13T15:00:00Z")
        );

        setCompletedAt(
                q14Session.getId(),
                Instant.parse("2026-09-20T14:59:59Z")
        );

        setCompletedAt(
                outsideCompletedSession.getId(),
                Instant.parse("2026-09-20T15:00:00Z")
        );

        // SQL로 변경한 완료 시각을 다음 조회에서 다시 읽도록 초기화
        entityManager.clear();

        WeeklyReportResponseDto report =
                weeklyReportsService.generateWeeklyReport(
                        user.getId(),
                        WEEK_START
                );

        assertThat(report.periodStart())
                .isEqualTo(WEEK_START);
        assertThat(report.periodEnd())
                .isEqualTo(LocalDate.of(2026, 9, 20));

        assertThat(report.recordCount())
                .isEqualTo(2);
        assertThat(report.averageIntensity())
                .isEqualTo(6.0);

        assertThat(report.emotionCounts())
                .containsEntry("SADNESS", 1L)
                .containsEntry("JOY", 1L)
                .doesNotContainKey("ANXIETY");

        assertThat(report.weekdayCounts())
                .containsEntry("MONDAY", 1L)
                .containsEntry("SUNDAY", 1L);

        assertThat(report.emotionRecordEvidences())
                .extracting(
                        evidence -> evidence.situationText()
                )
                .containsExactly(
                        "월요일 시작 경계 기록 상황",
                        "일요일 종료 직전 기록 상황"
                );

        assertThat(report.completedCbtCount())
                .isEqualTo(2);
        assertThat(report.averageHelpfulnessScore())
                .isEqualTo(3.0);

        assertThat(report.completedCbtEvidences())
                .extracting(
                        evidence -> evidence.sessionId()
                )
                .containsExactly(
                        legacySession.getId(),
                        q14Session.getId()
                );

        assertThat(report.completedCbtEvidences())
                .extracting(
                        evidence -> evidence.resultFormatVersion()
                )
                .containsExactly(
                        "legacy",
                        "cbt-insight-1"
                );

        assertThat(
                report.completedCbtEvidences()
                        .get(0)
                        .confirmedResult()
        ).isNull();

        assertThat(
                report.completedCbtEvidences()
                        .get(1)
                        .confirmedResult()
        ).isNotNull();

        assertThat(report.distortionChangeCounts())
                .containsKey("CONFIRMED_INSIGHT");

        assertThat(
                report.distortionChangeCounts()
                        .get("CONFIRMED_INSIGHT")
        ).containsEntry(
                "ALL_OR_NOTHING_THINKING",
                1L
        );
    }

    @Test
    void emptyWeekReturnsConflict() {
        Users emptyUser =
                usersRepository.saveAndFlush(
                        Users.create(
                                "empty-week@example.com",
                                "unused-password-hash",
                                "빈 주간 사용자"
                        )
                );

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                weeklyReportsService
                                        .generateWeeklyReport(
                                                emptyUser.getId(),
                                                WEEK_START
                                        )
                );

        assertThat(exception.getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    private EmotionRecords createRecord(
            String label,
            String emotionCode,
            short intensity,
            Instant occurredAt
    ) {
        EmotionRecords record =
                EmotionRecords.createQuick(
                        user,
                        label + " 원문",
                        InputType.TEXT,
                        occurredAt
                );

        record.confirm(
                new EmotionRecordsConfirmRequestDto(
                        label + " 상황",
                        label + " 자동 사고",
                        emotionCode,
                        intensity,
                        List.of(),
                        "OTHER",
                        "OTHER",
                        Map.of()
                )
        );

        return emotionRecordsRepository.saveAndFlush(
                record
        );
    }

    private ReflectionSessions createLegacyCompletedSession(
            EmotionRecords emotionRecord,
            short helpfulnessScore
    ) {
        ReflectionSessions session =
                ReflectionSessions.create(
                        user,
                        emotionRecord
                );

        session.confirm(
                new ReflectionSessionConfirmRequestDto(
                        "처음 생각을 뒷받침하는 근거",
                        "처음 생각과 다른 근거",
                        "균형 있게 다시 바라본 생각",
                        (short) 80,
                        (short) 35,
                        (short) 4,
                        helpfulnessScore,
                        List.of(),
                        List.of()
                )
        );

        return reflectionSessionsRepository.saveAndFlush(
                session
        );
    }

    private ReflectionSessions createQ14CompletedSession(
            EmotionRecords emotionRecord,
            short helpfulnessScore
    ) {
        ReflectionSessions session =
                ReflectionSessions.create(
                        user,
                        emotionRecord
                );

        Map<String, Object> confirmedResult =
                new LinkedHashMap<>();

        confirmedResult.put(
                "proposalId",
                "weekly-q14-proposal"
        );
        confirmedResult.put(
                "beforeText",
                "한 번 실패하면 전부 실패한 것이다"
        );
        confirmedResult.put(
                "evidenceForText",
                "한 번 기대한 결과가 나오지 않았다"
        );
        confirmedResult.put(
                "evidenceAgainstText",
                "이전에는 여러 번 해결한 경험이 있다"
        );
        confirmedResult.put(
                "afterText",
                "한 번의 결과만으로 전체를 판단할 수 없다"
        );
        confirmedResult.put(
                "reviews",
                List.of(
                        Map.of(
                                "code",
                                "ALL_OR_NOTHING_THINKING",
                                "reviewStatus",
                                "CONFIRMED"
                        )
                )
        );
        confirmedResult.put(
                "userConfirmed",
                true
        );

        Map<String, Object> insightState =
                new LinkedHashMap<>();

        insightState.put(
                "resultFormatVersion",
                "cbt-insight-1"
        );
        insightState.put(
                "confirmedResult",
                confirmedResult
        );

        session.replaceInsight(insightState);

        session.confirmInsight(
                confirmedResult,
                (short) 85,
                (short) 30,
                (short) 3,
                helpfulnessScore
        );

        return reflectionSessionsRepository.saveAndFlush(
                session
        );
    }

    private void setCompletedAt(
            Long sessionId,
            Instant completedAt
    ) {
        jdbcTemplate.update(
                """
                update reflection_sessions
                   set completed_at = ?
                 where id = ?
                """,
                Timestamp.from(completedAt),
                sessionId
        );
    }
}