// 최근 56일 반복 패턴의 기간 경계·등급·정렬·중복 제거를 실제 DB로 검증

package com.my.mindot_back.reports;

import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.reports.dto.RepeatedEmotionPatternDto;
import com.my.mindot_back.reports.entity.PatternLevel;
import com.my.mindot_back.reports.service.RepeatedEmotionPatternService;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Transactional
class RecentPatternIntegrationTest
        extends PostgresContainerTestBase {

    private static final ZoneId SEOUL =
            ZoneId.of("Asia/Seoul");

    private static final LocalDate REFERENCE_END =
            LocalDate.of(2026, 9, 20);

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private RepeatedEmotionPatternService
            repeatedEmotionPatternService;

    private Users user;

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(
                Users.create(
                        "recent-pattern@example.com",
                        "unused-password-hash",
                        "반복 패턴 사용자"
                )
        );
    }

    @Test
    void recentEightWeeksReturnsUniquePatternsInLevelOrder() {
        // 56일 범위 시작 경계에 걸친 5주 장기 패턴
        createRecord(
                "ANXIETY",
                at(
                        LocalDate.of(2026, 7, 27),
                        0,
                        0,
                        0
                )
        );
        createRecord(
                "ANXIETY",
                at(
                        LocalDate.of(2026, 8, 10),
                        0,
                        0,
                        0
                )
        );
        createRecord(
                "ANXIETY",
                at(
                        LocalDate.of(2026, 8, 24),
                        0,
                        0,
                        0
                )
        );

        // 3주 범위에서 두 주에 관찰된 지속 패턴
        createRecord(
                "SADNESS",
                at(
                        LocalDate.of(2026, 8, 25),
                        9,
                        0,
                        0
                )
        );
        createRecord(
                "SADNESS",
                at(
                        LocalDate.of(2026, 9, 8),
                        9,
                        0,
                        0
                )
        );

        // 연속된 두 주에 관찰된 반복 패턴
        createRecord(
                "ANGER",
                at(
                        LocalDate.of(2026, 9, 9),
                        13,
                        0,
                        0
                )
        );
        createRecord(
                "ANGER",
                at(
                        LocalDate.of(2026, 9, 16),
                        13,
                        0,
                        0
                )
        );

        // 기준일 종료 경계를 포함하는 최근 패턴
        createRecord(
                "JOY",
                at(
                        LocalDate.of(2026, 9, 20),
                        21,
                        0,
                        0
                )
        );
        createRecord(
                "JOY",
                at(
                        LocalDate.of(2026, 9, 20),
                        22,
                        0,
                        0
                )
        );
        createRecord(
                "JOY",
                at(
                        LocalDate.of(2026, 9, 20),
                        23,
                        59,
                        59
                )
        );

        // 범위 시작 직전이라 제외되어야 하는 패턴
        createRecord(
                "FEAR",
                at(
                        LocalDate.of(2026, 7, 26),
                        9,
                        0,
                        0
                )
        );
        createRecord(
                "FEAR",
                at(
                        LocalDate.of(2026, 7, 26),
                        10,
                        0,
                        0
                )
        );
        createRecord(
                "FEAR",
                at(
                        LocalDate.of(2026, 7, 26),
                        11,
                        0,
                        0
                )
        );

        // 기준일 다음 날이라 제외되어야 하는 패턴
        createRecord(
                "CALM",
                at(
                        LocalDate.of(2026, 9, 21),
                        9,
                        0,
                        0
                )
        );
        createRecord(
                "CALM",
                at(
                        LocalDate.of(2026, 9, 21),
                        10,
                        0,
                        0
                )
        );
        createRecord(
                "CALM",
                at(
                        LocalDate.of(2026, 9, 21),
                        11,
                        0,
                        0
                )
        );

        List<RepeatedEmotionPatternDto> patterns =
                repeatedEmotionPatternService
                        .analyzeRecentEightWeeks(
                                user.getId(),
                                REFERENCE_END
                        );

        assertThat(patterns)
                .hasSize(4);

        assertThat(patterns)
                .extracting(
                        RepeatedEmotionPatternDto::patternLevel
                )
                .containsExactly(
                        PatternLevel.LONG_TERM,
                        PatternLevel.SUSTAINED,
                        PatternLevel.REPEATED,
                        PatternLevel.RECENT
                );

        assertThat(patterns)
                .extracting(
                        RepeatedEmotionPatternDto::emotionCode,
                        RepeatedEmotionPatternDto::weekday,
                        RepeatedEmotionPatternDto::timeBucket,
                        RepeatedEmotionPatternDto::occurrenceCount,
                        RepeatedEmotionPatternDto::distinctDateCount,
                        RepeatedEmotionPatternDto::observedWeekCount,
                        RepeatedEmotionPatternDto::patternLevel
                )
                .containsExactly(
                        tuple(
                                "ANXIETY",
                                "MONDAY",
                                "DAWN",
                                3L,
                                3L,
                                3L,
                                PatternLevel.LONG_TERM
                        ),
                        tuple(
                                "SADNESS",
                                "TUESDAY",
                                "MORNING",
                                2L,
                                2L,
                                2L,
                                PatternLevel.SUSTAINED
                        ),
                        tuple(
                                "ANGER",
                                "WEDNESDAY",
                                "AFTERNOON",
                                2L,
                                2L,
                                2L,
                                PatternLevel.REPEATED
                        ),
                        tuple(
                                "JOY",
                                "SUNDAY",
                                "NIGHT",
                                3L,
                                1L,
                                1L,
                                PatternLevel.RECENT
                        )
                );

        assertThat(patterns)
                .extracting(
                        RepeatedEmotionPatternDto::emotionCode
                )
                .doesNotContain(
                        "FEAR",
                        "CALM"
                );

        long uniquePatternCount =
                patterns.stream()
                        .map(pattern ->
                                pattern.emotionCode()
                                        + "|"
                                        + pattern.weekday()
                                        + "|"
                                        + pattern.timeBucket()
                        )
                        .collect(
                                java.util.stream.Collectors
                                        .toCollection(HashSet::new)
                        )
                        .size();

        assertThat(uniquePatternCount)
                .isEqualTo(patterns.size());
    }

    private EmotionRecords createRecord(
            String emotionCode,
            Instant occurredAt
    ) {
        EmotionRecords record =
                EmotionRecords.createQuick(
                        user,
                        emotionCode + " 반복 패턴 기록",
                        InputType.TEXT,
                        occurredAt
                );

        record.confirm(
                new EmotionRecordsConfirmRequestDto(
                        emotionCode + " 상황",
                        emotionCode + " 자동 사고",
                        emotionCode,
                        (short) 6,
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

    private Instant at(
            LocalDate date,
            int hour,
            int minute,
            int second
    ) {
        return LocalDateTime.of(
                        date.getYear(),
                        date.getMonthValue(),
                        date.getDayOfMonth(),
                        hour,
                        minute,
                        second
                )
                .atZone(SEOUL)
                .toInstant();
    }
}