// 최근 8주 감정 기록에서 반복되는 감정·요일·시간대 패턴을 계산하는 Service
package com.my.mindot_back.reports.service;

import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.CompletionStatus;
import com.my.mindot_back.records.entity.TimeBucket;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.reports.dto.RepeatedEmotionPatternDto;
import com.my.mindot_back.reports.entity.PatternLevel;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class RepeatedEmotionPatternService {

    private final EmotionRecordsRepository emotionRecordsRepository;
    private final UsersRepository usersRepository;

    // 감정·요일·시간대 조합을 패턴별로 묶기 위한 내부 키
    private record EmotionPatternKey(
            String emotionCode,
            String weekday,
            String timeBucket
    ) {
    }

    // 하나의 패턴을 판단하는 데 사용하는 집계값
    private record PatternStatistics(
            long occurrenceCount,
            long distinctDateCount,
            long observedWeekCount,
            long consecutiveWeekCount,
            long patternSpanWeekCount
    ) {
    }

    /*
     * 지정한 기준일까지 총 56일을 조회해 최근 8주 반복 패턴을 계산
     *
     * 주간 리포트는 선택한 주의 일요일을 기준일로 전달
     */
    public List<RepeatedEmotionPatternDto> analyzeRecentEightWeeks(
            Long userId,
            LocalDate referenceEnd
    ) {
        Users user = findUser(userId);

        return analyzeRecentEightWeeks(
                user,
                referenceEnd
        );
    }

    // 알림·추천 기능을 위해 사용자 현지 날짜의 오늘을 기준으로 최근 8주 반복 패턴을 계산
    public List<RepeatedEmotionPatternDto> analyzeRecentEightWeeksUpToToday(
            Long userId
    ) {
        Users user = findUser(userId);
        ZoneId zoneId = ZoneId.of(user.getTimezone());

        return analyzeRecentEightWeeks(
                user,
                LocalDate.now(zoneId)
        );
    }

    // 사용자 존재 여부 확인
    private Users findUser(Long userId) {
        return usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));
    }

    // 조회한 사용자와 기준일을 사용해 실제 최근 8주 패턴 계산
    private List<RepeatedEmotionPatternDto> analyzeRecentEightWeeks(
            Users user,
            LocalDate referenceEnd
    ) {
        if (referenceEnd == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "반복 패턴 기준일이 필요합니다."
            );
        }

        ZoneId zoneId = ZoneId.of(user.getTimezone());

        // 기준일을 포함해 정확히 56일 조회
        LocalDate periodStart = referenceEnd.minusDays(55);

        Instant periodStartInstant =
                periodStart.atStartOfDay(zoneId).toInstant();

        Instant periodEndExclusive =
                referenceEnd.plusDays(1)
                        .atStartOfDay(zoneId)
                        .toInstant();

        List<EmotionRecords> emotionRecords =
                emotionRecordsRepository
                        .findAllByUser_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(
                                user.getId(),
                                periodStartInstant,
                                periodEndExclusive
                        );

        return createRepeatedEmotionPatterns(
                emotionRecords,
                zoneId
        );
    }

    // 최근 8주 기록에서 반복 기준을 만족한 패턴 목록 생성
    private List<RepeatedEmotionPatternDto> createRepeatedEmotionPatterns(
            List<EmotionRecords> emotionRecords,
            ZoneId zoneId
    ) {
        return groupEmotionRecordsByPattern(
                emotionRecords,
                zoneId
        )
                .entrySet()
                .stream()
                .map(entry -> {
                    EmotionPatternKey patternKey = entry.getKey();
                    List<EmotionRecords> patternRecords = entry.getValue();

                    PatternStatistics statistics =
                            calculatePatternStatistics(
                                    patternRecords,
                                    zoneId
                            );

                    PatternLevel patternLevel =
                            determinePatternLevel(statistics);

                    // 반복 기준을 만족하지 않으면 응답에서 제외
                    if (patternLevel == null) {
                        return null;
                    }

                    long distinctWeekdayCount =
                            patternRecords.stream()
                                    .map(record -> record.getOccurredAt()
                                            .atZone(zoneId)
                                            .getDayOfWeek()
                                    )
                                    .distinct()
                                    .count();

                    /*
                     * 모든 기록이 특정 요일 하나에서만 발생했다면
                     * 요일 없는 시간대 패턴은 동일 내용을 중복 표시하므로 제외.
                     */
                    if (patternKey.weekday() == null
                            && distinctWeekdayCount == 1) {
                        return null;
                    }

                    return new RepeatedEmotionPatternDto(
                            patternKey.emotionCode(),
                            patternKey.weekday(),
                            patternKey.timeBucket(),
                            statistics.occurrenceCount(),
                            statistics.distinctDateCount(),
                            statistics.observedWeekCount(),
                            patternLevel
                    );
                })
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(
                                RepeatedEmotionPatternDto::patternLevel
                        )
                        .reversed()
                        .thenComparing(Comparator
                                .comparingLong(
                                        RepeatedEmotionPatternDto
                                                ::observedWeekCount
                                )
                                .reversed()
                        )
                        .thenComparing(Comparator
                                .comparingLong(
                                        RepeatedEmotionPatternDto
                                                ::occurrenceCount
                                )
                                .reversed()
                        )
                )
                .toList();
    }

    // 기록을 감정·시간대 또는 감정·요일·시간대 조합으로 묶음
    private Map<EmotionPatternKey, List<EmotionRecords>>
    groupEmotionRecordsByPattern(
            List<EmotionRecords> emotionRecords,
            ZoneId zoneId
    ) {
        return emotionRecords.stream()
                .filter(record ->
                        record.getCompletionStatus() == CompletionStatus.COMPLETE
                                && record.getPrimaryEmotionCode() != null
                                && !record.getPrimaryEmotionCode().isBlank()
                                && record.getOccurredAt() != null
                )
                .flatMap(record -> {
                    String emotionCode =
                            record.getPrimaryEmotionCode();

                    String timeBucket =
                            TimeBucket.fromHour(record.getOccurredAt()
                                    .atZone(zoneId).getHour()).name();

                    String weekday = record.getOccurredAt()
                            .atZone(zoneId)
                            .getDayOfWeek()
                            .name();

                    return Stream.of(
                                    // 여러 요일에 걸친 동일 감정·시간대 패턴
                                    new EmotionPatternKey(
                                            emotionCode,
                                            null,
                                            timeBucket
                                    ),

                                    // 특정 요일에 반복된 감정·시간대 패턴
                                    new EmotionPatternKey(
                                            emotionCode,
                                            weekday,
                                            timeBucket
                                    )
                            )
                            .map(patternKey -> Map.entry(
                                    patternKey,
                                    record
                            ));
                })
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        LinkedHashMap::new,
                        Collectors.mapping(
                                Map.Entry::getValue,
                                Collectors.toList()
                        )
                ));
    }

    // 하나의 패턴 그룹에서 날짜와 주 단위 반복 정도 계산
    private PatternStatistics calculatePatternStatistics(
            List<EmotionRecords> emotionRecords,
            ZoneId zoneId
    ) {
        List<LocalDate> distinctDates =
                emotionRecords.stream()
                        .map(record -> record.getOccurredAt()
                                .atZone(zoneId)
                                .toLocalDate()
                        )
                        .distinct()
                        .toList();

        List<LocalDate> observedWeekStarts =
                distinctDates.stream()
                        .map(date -> date.with(DayOfWeek.MONDAY))
                        .distinct()
                        .sorted()
                        .toList();

        long longestConsecutiveWeeks = 0;
        long currentConsecutiveWeeks = 0;
        LocalDate previousWeekStart = null;

        for (LocalDate observedWeekStart : observedWeekStarts) {
            if (previousWeekStart != null
                    && observedWeekStart.equals(
                    previousWeekStart.plusWeeks(1)
            )) {
                currentConsecutiveWeeks++;
            } else {
                currentConsecutiveWeeks = 1;
            }

            longestConsecutiveWeeks = Math.max(
                    longestConsecutiveWeeks,
                    currentConsecutiveWeeks
            );

            previousWeekStart = observedWeekStart;
        }

        long patternSpanWeekCount =
                observedWeekStarts.isEmpty()
                        ? 0
                        : ChronoUnit.WEEKS.between(
                        observedWeekStarts.get(0),
                        observedWeekStarts.get(
                                observedWeekStarts.size() - 1
                        )
                ) + 1;

        return new PatternStatistics(
                emotionRecords.size(),
                distinctDates.size(),
                observedWeekStarts.size(),
                longestConsecutiveWeeks,
                patternSpanWeekCount
        );
    }

    // 기록 기간과 반복 횟수로 패턴 지속 등급 결정
    private PatternLevel determinePatternLevel(
            PatternStatistics statistics
    ) {
        // 5~8주 범위에서 3주 이상, 서로 다른 날짜 3일 이상 기록
        if (statistics.patternSpanWeekCount() >= 5
                && statistics.observedWeekCount() >= 3
                && statistics.distinctDateCount() >= 3) {
            return PatternLevel.LONG_TERM;
        }

        // 4주 범위에서 3주 이상 기록
        if (statistics.patternSpanWeekCount() == 4
                && statistics.observedWeekCount() >= 3) {
            return PatternLevel.SUSTAINED;
        }

        // 3주 범위에서 2주 이상 기록
        if (statistics.patternSpanWeekCount() == 3
                && statistics.observedWeekCount() >= 2) {
            return PatternLevel.SUSTAINED;
        }

        // 연속된 2주 이상 기록
        if (statistics.consecutiveWeekCount() >= 2) {
            return PatternLevel.REPEATED;
        }

        // 한 주 안에서 동일한 감정·시간대가 3회 이상 기록
        if (statistics.patternSpanWeekCount() == 1
                && statistics.occurrenceCount() >= 3) {
            return PatternLevel.RECENT;
        }

        return null;
    }
}
