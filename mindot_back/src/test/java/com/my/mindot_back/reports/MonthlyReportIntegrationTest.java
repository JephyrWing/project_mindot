// 월간 리포트의 월 경계·중복 생성·원본 변경 반영을 실제 DB로 검증

package com.my.mindot_back.reports;

import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.dto.ReflectionSessionConfirmRequestDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.reports.dto.MonthlyIntensityTrend;
import com.my.mindot_back.reports.dto.MonthlyReportDailyTrendDto;
import com.my.mindot_back.reports.dto.MonthlyReportResponseDto;
import com.my.mindot_back.reports.service.MonthlyReportsService;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MonthlyReportIntegrationTest
        extends PostgresContainerTestBase {

    private static final YearMonth REPORT_MONTH =
            YearMonth.of(2026, 9);

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private ReflectionSessionsRepository
            reflectionSessionsRepository;

    @Autowired
    private MonthlyReportsService monthlyReportsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Users user;

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(
                Users.create(
                        "monthly-report@example.com",
                        "unused-password-hash",
                        "월간 리포트 사용자"
                )
        );
    }

    @AfterEach
    void cleanUp() {
        if (user != null && user.getId() != null) {
            jdbcTemplate.update(
                    "delete from users where id = ?",
                    user.getId()
            );
        }
    }

    @Test
    void regenerationKeepsOneReportAndReflectsLatestSources()
            throws Exception {
        createRecord(
                "월 시작 직전 기록",
                "SADNESS",
                (short) 10,
                "WORK",
                Instant.parse("2026-08-31T14:59:59Z")
        );

        EmotionRecords firstRecord =
                createRecord(
                        "9월 1일 기록",
                        "ANXIETY",
                        (short) 4,
                        "WORK",
                        Instant.parse("2026-08-31T15:00:00Z")
                );

        EmotionRecords lastRecord =
                createRecord(
                        "9월 30일 기록",
                        "ANXIETY",
                        (short) 8,
                        "WORK",
                        Instant.parse("2026-09-30T14:59:59Z")
                );

        createRecord(
                "다음 달 시작 기록",
                "JOY",
                (short) 9,
                "REST",
                Instant.parse("2026-09-30T15:00:00Z")
        );

        MonthlyReportResponseDto initial =
                monthlyReportsService.generateMonthlyReport(
                        user.getId(),
                        REPORT_MONTH
                );

        assertThat(initial.periodStart())
                .isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(initial.periodEnd())
                .isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(initial.recordCount())
                .isEqualTo(2);
        assertThat(initial.averageIntensity())
                .isEqualTo(6.0);
        assertThat(initial.firstHalfAverageIntensity())
                .isEqualTo(4.0);
        assertThat(initial.secondHalfAverageIntensity())
                .isEqualTo(8.0);
        assertThat(initial.intensityTrend())
                .isEqualTo(
                        MonthlyIntensityTrend.INCREASED
                );
        assertThat(initial.dailyTrends())
                .hasSize(30);

        assertThat(
                dailyTrend(
                        initial,
                        LocalDate.of(2026, 9, 1)
                ).recordCount()
        ).isEqualTo(1);

        assertThat(
                dailyTrend(
                        initial,
                        LocalDate.of(2026, 9, 30)
                ).recordCount()
        ).isEqualTo(1);

        MonthlyReportResponseDto repeated =
                monthlyReportsService.generateMonthlyReport(
                        user.getId(),
                        REPORT_MONTH
                );

        assertThat(repeated.reportId())
                .isEqualTo(initial.reportId());
        assertThat(monthlyReportCount())
                .isEqualTo(1);

        verifyConcurrentGenerationUsesSameReport(
                initial.reportId()
        );

        firstRecord.updateOccurredAt(
                Instant.parse("2026-09-19T15:00:00Z")
        );

        firstRecord.confirm(
                new EmotionRecordsConfirmRequestDto(
                        "수정된 9월 20일 상황",
                        "수정된 자동 사고",
                        "JOY",
                        (short) 2,
                        List.of(),
                        "REST",
                        "OTHER",
                        Map.of()
                )
        );

        emotionRecordsRepository.saveAndFlush(
                firstRecord
        );

        emotionRecordsRepository.deleteById(
                lastRecord.getId()
        );
        emotionRecordsRepository.flush();

        ReflectionSessions completedSession =
                ReflectionSessions.create(
                        user,
                        firstRecord
                );

        completedSession.confirm(
                new ReflectionSessionConfirmRequestDto(
                        "처음 생각을 뒷받침하는 근거",
                        "처음 생각과 다른 근거",
                        "조금 더 균형 있게 바라본 생각",
                        (short) 80,
                        (short) 30,
                        (short) 3,
                        (short) 5,
                        List.of(),
                        List.of()
                )
        );

        completedSession =
                reflectionSessionsRepository.saveAndFlush(
                        completedSession
                );

        jdbcTemplate.update(
                """
                update reflection_sessions
                   set completed_at = ?
                 where id = ?
                """,
                Timestamp.from(
                        Instant.parse(
                                "2026-09-25T03:00:00Z"
                        )
                ),
                completedSession.getId()
        );

        MonthlyReportResponseDto regenerated =
                monthlyReportsService.generateMonthlyReport(
                        user.getId(),
                        REPORT_MONTH
                );

        assertThat(regenerated.reportId())
                .isEqualTo(initial.reportId());
        assertThat(regenerated.recordCount())
                .isEqualTo(1);
        assertThat(regenerated.dominantEmotionCode())
                .isEqualTo("JOY");
        assertThat(regenerated.averageIntensity())
                .isEqualTo(2.0);
        assertThat(regenerated.emotionCounts())
                .containsExactly(
                        Map.entry("JOY", 1L)
                );
        assertThat(regenerated.contextCategoryCounts())
                .containsExactly(
                        Map.entry("REST", 1L)
                );

        assertThat(
                dailyTrend(
                        regenerated,
                        LocalDate.of(2026, 9, 1)
                ).recordCount()
        ).isZero();

        MonthlyReportDailyTrendDto updatedTrend =
                dailyTrend(
                        regenerated,
                        LocalDate.of(2026, 9, 20)
                );

        assertThat(updatedTrend.recordCount())
                .isEqualTo(1);
        assertThat(updatedTrend.averageIntensity())
                .isEqualTo(2.0);
        assertThat(updatedTrend.dominantEmotionCode())
                .isEqualTo("JOY");

        assertThat(
                dailyTrend(
                        regenerated,
                        LocalDate.of(2026, 9, 30)
                ).recordCount()
        ).isZero();

        assertThat(regenerated.completedCbtCount())
                .isEqualTo(1);
        assertThat(regenerated.averageHelpfulnessScore())
                .isEqualTo(5.0);
        assertThat(monthlyReportCount())
                .isEqualTo(1);

        MonthlyReportResponseDto stored =
                monthlyReportsService.getMonthlyReport(
                        user.getId(),
                        REPORT_MONTH
                );

        assertThat(stored.reportId())
                .isEqualTo(initial.reportId());
        assertThat(stored.recordCount())
                .isEqualTo(1);
        assertThat(stored.completedCbtCount())
                .isEqualTo(1);
        assertThat(
                dailyTrend(
                        stored,
                        LocalDate.of(2026, 9, 20)
                ).recordCount()
        ).isEqualTo(1);
    }

    private void verifyConcurrentGenerationUsesSameReport(
            Long expectedReportId
    ) throws Exception {
        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch ready =
                new CountDownLatch(2);
        CountDownLatch start =
                new CountDownLatch(1);

        try {
            Future<Long> first =
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();

                        return monthlyReportsService
                                .generateMonthlyReport(
                                        user.getId(),
                                        REPORT_MONTH
                                )
                                .reportId();
                    });

            Future<Long> second =
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();

                        return monthlyReportsService
                                .generateMonthlyReport(
                                        user.getId(),
                                        REPORT_MONTH
                                )
                                .reportId();
                    });

            assertThat(
                    ready.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            start.countDown();

            assertThat(first.get(
                    20,
                    TimeUnit.SECONDS
            )).isEqualTo(expectedReportId);

            assertThat(second.get(
                    20,
                    TimeUnit.SECONDS
            )).isEqualTo(expectedReportId);

            assertThat(monthlyReportCount())
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private EmotionRecords createRecord(
            String label,
            String emotionCode,
            short intensity,
            String contextCategory,
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
                        contextCategory,
                        "OTHER",
                        Map.of()
                )
        );

        return emotionRecordsRepository.saveAndFlush(
                record
        );
    }

    private MonthlyReportDailyTrendDto dailyTrend(
            MonthlyReportResponseDto report,
            LocalDate date
    ) {
        return report.dailyTrends()
                .stream()
                .filter(trend ->
                        trend.date().equals(date)
                )
                .findFirst()
                .orElseThrow();
    }

    private long monthlyReportCount() {
        Long count = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from reports
                 where user_id = ?
                   and report_type = 'MONTHLY'
                   and period_start = ?
                   and period_end = ?
                """,
                Long.class,
                user.getId(),
                Date.valueOf(REPORT_MONTH.atDay(1)),
                Date.valueOf(REPORT_MONTH.atEndOfMonth())
        );

        return count == null ? 0L : count;
    }
}