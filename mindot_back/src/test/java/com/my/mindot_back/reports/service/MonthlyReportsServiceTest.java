// 월간 리포트의 기간 집계·강도 흐름·빈 달 처리·JSON 날짜 복원을 검증
package com.my.mindot_back.reports.service;

import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.reports.dto.MonthlyIntensityTrend;
import com.my.mindot_back.reports.entity.ReportType;
import com.my.mindot_back.reports.entity.Reports;
import com.my.mindot_back.reports.repository.ReportsRepository;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonthlyReportsServiceTest {

    private ReportsRepository reportsRepository;
    private EmotionRecordsRepository emotionRecordsRepository;
    private ReflectionSessionsRepository reflectionSessionsRepository;
    private UsersRepository usersRepository;
    private MonthlyReportsService service;

    @BeforeEach
    void setUp() {
        reportsRepository = mock(ReportsRepository.class);
        emotionRecordsRepository =
                mock(EmotionRecordsRepository.class);
        reflectionSessionsRepository =
                mock(ReflectionSessionsRepository.class);
        usersRepository = mock(UsersRepository.class);

        service = new MonthlyReportsService(
                reportsRepository,
                emotionRecordsRepository,
                reflectionSessionsRepository,
                usersRepository
        );
    }

    @Test
    void generateCalculatesMonthlyIntensityTrendAndDailyData() {
        Users user = mock(Users.class);
        when(user.getTimezone()).thenReturn("Asia/Seoul");
        when(usersRepository.findLockedById(7L))
                .thenReturn(Optional.of(user));

        EmotionRecords firstHalfRecord = emotionRecord(
                "2026-09-03T01:00:00Z",
                "ANXIETY",
                (short) 4,
                "WORK"
        );
        EmotionRecords secondHalfRecord = emotionRecord(
                "2026-09-20T01:00:00Z",
                "ANXIETY",
                (short) 8,
                "WORK"
        );

        when(emotionRecordsRepository
                .findAllByUser_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(
                        any(),
                        any(),
                        any()
                ))
                .thenReturn(List.of(
                        firstHalfRecord,
                        secondHalfRecord
                ));

        ReflectionSessions reflection = mock(ReflectionSessions.class);
        when(reflection.getHelpfulnessScore()).thenReturn((short) 5);

        when(reflectionSessionsRepository
                .findAllByUser_IdAndStatusAndUserConfirmedTrueAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                        any(),
                        any(),
                        any(),
                        any()
                ))
                .thenReturn(List.of(reflection));

        when(reportsRepository
                .findByUser_IdAndReportTypeAndPeriodStartAndPeriodEnd(
                        7L,
                        ReportType.MONTHLY,
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 30)
                ))
                .thenReturn(Optional.empty());

        when(reportsRepository.save(any(Reports.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.generateMonthlyReport(
                7L,
                YearMonth.of(2026, 9)
        );

        assertThat(response.periodStart())
                .isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(response.periodEnd())
                .isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(response.recordCount()).isEqualTo(2);
        assertThat(response.averageIntensity()).isEqualTo(6.0);
        assertThat(response.firstHalfAverageIntensity())
                .isEqualTo(4.0);
        assertThat(response.secondHalfAverageIntensity())
                .isEqualTo(8.0);
        assertThat(response.intensityTrend())
                .isEqualTo(MonthlyIntensityTrend.INCREASED);
        assertThat(response.completedCbtCount()).isEqualTo(1);
        assertThat(response.averageHelpfulnessScore())
                .isEqualTo(5.0);
        assertThat(response.dailyTrends()).hasSize(30);
    }

    @Test
    void generateRejectsMonthWithoutRecordsAndReflections() {
        Users user = mock(Users.class);
        when(user.getTimezone()).thenReturn("Asia/Seoul");
        when(usersRepository.findLockedById(7L))
                .thenReturn(Optional.of(user));

        when(emotionRecordsRepository
                .findAllByUser_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(
                        any(),
                        any(),
                        any()
                ))
                .thenReturn(List.of());

        when(reflectionSessionsRepository
                .findAllByUser_IdAndStatusAndUserConfirmedTrueAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                        any(),
                        any(),
                        any(),
                        any()
                ))
                .thenReturn(List.of());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.generateMonthlyReport(
                        7L,
                        YearMonth.of(2026, 7)
                )
        );

        assertThat(exception.getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getRestoresArrayFormattedDateFromStoredJson() {
        Users user = mock(Users.class);

        Map<String, Object> storedDailyTrend =
                new LinkedHashMap<>();
        storedDailyTrend.put("date", List.of(2026, 9, 1));
        storedDailyTrend.put("recordCount", 2);
        storedDailyTrend.put("averageIntensity", 6.0);
        storedDailyTrend.put(
                "dominantEmotionCode",
                "ANXIETY"
        );

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("dailyTrends", List.of(storedDailyTrend));

        Reports report = Reports.createMonthly(
                user,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30),
                content
        );

        when(reportsRepository
                .findByUser_IdAndReportTypeAndPeriodStartAndPeriodEnd(
                        7L,
                        ReportType.MONTHLY,
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 30)
                ))
                .thenReturn(Optional.of(report));

        var response = service.getMonthlyReport(
                7L,
                YearMonth.of(2026, 9)
        );

        assertThat(response.dailyTrends()).hasSize(1);
        assertThat(response.dailyTrends().get(0).date())
                .isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(response.dailyTrends().get(0).recordCount())
                .isEqualTo(2);
    }

    private EmotionRecords emotionRecord(
            String occurredAt,
            String emotionCode,
            short intensity,
            String contextCategory
    ) {
        EmotionRecords record = mock(EmotionRecords.class);

        when(record.getOccurredAt())
                .thenReturn(Instant.parse(occurredAt));
        when(record.getPrimaryEmotionCode())
                .thenReturn(emotionCode);
        when(record.getPrimaryIntensity())
                .thenReturn(intensity);
        when(record.getContextCategory())
                .thenReturn(contextCategory);

        return record;
    }
}
