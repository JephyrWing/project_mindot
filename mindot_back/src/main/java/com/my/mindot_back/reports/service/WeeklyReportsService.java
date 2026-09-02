// 선택한 주의 감정 기록을 집계해 주간 리포트를 생성·갱신하는 Service
package com.my.mindot_back.reports.service;

import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.reports.dto.RepeatedEmotionPatternDto;
import com.my.mindot_back.reports.dto.WeeklyReportCbtEvidenceDto;
import com.my.mindot_back.reports.dto.WeeklyReportEmotionEvidenceDto;
import com.my.mindot_back.reports.dto.WeeklyReportResponseDto;
import com.my.mindot_back.reports.entity.PatternLevel;
import com.my.mindot_back.reports.entity.ReportType;
import com.my.mindot_back.reports.entity.Reports;
import com.my.mindot_back.reports.repository.ReportsRepository;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class WeeklyReportsService {

    // 기존 주간 리포트를 조회하거나 새 리포트를 저장
    private final ReportsRepository reportsRepository;

    // 선택한 기간의 감정 기록 조회
    private final EmotionRecordsRepository emotionRecordsRepository;

    // 선택한 기간에 완료, 확정된 CBT 성찰 세션 조회
    private final ReflectionSessionsRepository reflectionSessionsRepository;

    // 사용자 시간대와 사용자 존재 여부 확인
    private final UsersRepository usersRepository;

    // 감정, 요일, 시간대 조합을 묶어 계산하기 위한 내부 키
    private record EmotionPatternKey(
            String emotionCode,
            String weekday,
            String timeBucket
    ){
    }

    // 반복 패턴 판단에 필요한 집계값
    private record PatternStatistics(
            long occurrenceCount,
            long distinctDateCount,
            long observedWeekCount,
            long consecutiveWeekCount,
            long patternSpanWeekCount
    ) {
    }

    // 하나의 패턴 그룹에서 날짜와 주 단위 반복 정도 계산
    private PatternStatistics calculatePatternStatistics(
            List<EmotionRecords> emotionRecords,
            ZoneId zoneId
            ){
        List<LocalDate> distinctDates = emotionRecords.stream()
                .map(emotionRecord -> emotionRecord.getOccurredAt()
                        .atZone(zoneId)
                        .toLocalDate()
                )
                .distinct()
                .toList();

        List<LocalDate> observedWeekStarts = distinctDates.stream()
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

        long patternSpanWeekCount = observedWeekStarts.isEmpty()
                ? 0
                : ChronoUnit.WEEKS.between(
                        observedWeekStarts.get(0),
                        observedWeekStarts.get(observedWeekStarts.size() - 1)
        ) + 1;

        return new PatternStatistics(
                emotionRecords.size(),
                distinctDates.size(),
                observedWeekStarts.size(),
                longestConsecutiveWeeks,
                patternSpanWeekCount
        );
    }

    // 기록 기간과 반복 횟수를 기준으로 패턴 강도 결정
    private PatternLevel determinePatternLevel(
            PatternStatistics statistics
    ) {
        // 5~8주 범위에 3주 이상, 서로 다른 날짜 3일 이상 기록
        if (statistics.patternSpanWeekCount() >= 5
                && statistics.observedWeekCount() >= 3
                && statistics.distinctDateCount() >= 3) {
            return PatternLevel.LONG_TERM;
        }

        // 4주 중 3주 이상 기록
        if (statistics.patternSpanWeekCount() == 4
                && statistics.observedWeekCount() >= 3) {
            return PatternLevel.SUSTAINED;
        }

        // 3주 중 2주 이상 기록
        if (statistics.patternSpanWeekCount() == 3
                && statistics.observedWeekCount() >= 2) {
            return PatternLevel.SUSTAINED;
        }

        // 연속된 2주에 기록
        if (statistics.consecutiveWeekCount() >= 2) {
            return PatternLevel.REPEATED;
        }

        // 한 주 안에서 같은 감정·시간대가 3회 이상 기록
        if (statistics.patternSpanWeekCount() == 1
                && statistics.occurrenceCount() >= 3) {
            return PatternLevel.RECENT;
        }

        return null;
    }

    // 최근 8주 기록에서 사용자에게 보여줄 반복 패턴 목록 생성
    private List<RepeatedEmotionPatternDto> createRepeatedEmotionPatterns(
            List<EmotionRecords> emotionRecords,
            ZoneId zoneId
    ) {
        return groupEmotionRecordsByPattern(emotionRecords, zoneId)
                .entrySet()
                .stream()
                .map(entry -> {
                    EmotionPatternKey patternKey = entry.getKey();
                    List<EmotionRecords> patternRecords = entry.getValue();

                    PatternStatistics statistics = calculatePatternStatistics(
                            patternRecords,
                            zoneId
                    );
                    PatternLevel patternLevel = determinePatternLevel(statistics);

                    // 반복 기준을 만족하지 않은 그룹은 응답에서 제외
                    if (patternLevel == null) {
                        return null;
                    }

                    long distinctWeekdayCount = patternRecords.stream()
                            .map(emotionRecord -> emotionRecord.getOccurredAt()
                                    .atZone(zoneId)
                                    .getDayOfWeek()
                            )
                            .distinct()
                            .count();

                    // 특정 요일만 기록된 경우 시간대 중심 패턴은 중복되므로 제외
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
                        .comparing(RepeatedEmotionPatternDto::patternLevel)
                        .reversed()
                        .thenComparing(Comparator
                                .comparingLong(
                                        RepeatedEmotionPatternDto::observedWeekCount
                                )
                                .reversed()
                        )
                        .thenComparing(Comparator
                                .comparingLong(
                                        RepeatedEmotionPatternDto::occurrenceCount
                                )
                                .reversed()
                        )
                )
                .toList();
    }


    // 확정된 기록을 감정, 시간대 및 감정, 요일, 시간대 패턴으로 묶음
    private Map<EmotionPatternKey, List<EmotionRecords>>
    groupEmotionRecordsByPattern(
            List<EmotionRecords> emotionRecords,
            ZoneId zoneId
    ) {
        return emotionRecords.stream()
                .filter(emotionRecord ->
                        emotionRecord.getPrimaryEmotionCode() != null
                                && !emotionRecord.getPrimaryEmotionCode().isBlank()
                )
                .flatMap(emotionRecord -> {
                    String emotionCode = emotionRecord.getPrimaryEmotionCode();
                    String timeBucket = emotionRecord.getTimeBucket().name();
                    String weekday = emotionRecord.getOccurredAt()
                            .atZone(zoneId)
                            .getDayOfWeek()
                            .name();

                    return Stream.of(
                                    new EmotionPatternKey(
                                            emotionCode,
                                            null,
                                            timeBucket
                                    ),
                                    new EmotionPatternKey(
                                            emotionCode,
                                            weekday,
                                            timeBucket
                                    )
                            )
                            .map(patternKey -> Map.entry(
                                    patternKey,
                                    emotionRecord
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

    // 주간 리포트 시작일은 항상 월요일인지 확인
    private void validateWeekStart(
            LocalDate weekStart
    ) {
        if (weekStart == null
                || weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "weekStart는 월요일 날짜여야 합니다."
            );
        }
    }

    // 사용자 시간대를 기준으로 선택한 주의 감정 기록 조회
    private List<EmotionRecords> findWeeklyEmotionRecords(
            Long userId,
            LocalDate weekStart
    ) {
        // 월요일 시작일인지 먼저 검증
        validateWeekStart(weekStart);

        // 사용자 존재 여부와 개인 시간대 확인
        Users user = usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));

        // 사용자의 시간대를 기준으로 월요일 00:00 계산
        ZoneId zoneId = ZoneId.of(user.getTimezone());
        Instant periodStart = weekStart.atStartOfDay(zoneId).toInstant();

        // 다음 주 월요일 00:00 직전까지 포함
        Instant periodEndExclusive = weekStart
                .plusDays(7)
                .atStartOfDay(zoneId)
                .toInstant();

        // 기간 내 감정 기록을 오래된 순으로 조회
        return emotionRecordsRepository
                .findAllByUser_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(
                        userId,
                        periodStart,
                        periodEndExclusive
                );
    }

    // 선택한 주를 포함한 최근 8주의 감정 기록 조회
    private List<EmotionRecords> findRecentEightWeeksEmotionRecords(
            Long userId,
            LocalDate weekStart
    ) {
        validateWeekStart(weekStart);

        Users user = usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));

        ZoneId zoneId = ZoneId.of(user.getTimezone());
        LocalDate patternStart = weekStart.minusWeeks(7);
        Instant periodStart = patternStart.atStartOfDay(zoneId).toInstant();
        Instant periodEndExclusive = weekStart.plusWeeks(1)
                .atStartOfDay(zoneId)
                .toInstant();

        return emotionRecordsRepository
                .findAllByUser_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(
                        userId,
                        periodStart,
                        periodEndExclusive
                );
    }

    // 사용자 시간대를 기준으로 선택한 주에 완료, 확정된 CBT 세션 조회
    private List<ReflectionSessions> findWeeklyCompletedReflectionSessions(
            Long userId,
            LocalDate weekStart
    ) {
        // 월요일이 시작일인지 검증
        validateWeekStart(weekStart);

        // 사용자 시간대를 기준으로 기간 경계 계산
        Users user = usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));

        ZoneId zoneId = ZoneId.of(user.getTimezone());
        Instant periodStart = weekStart.atStartOfDay(zoneId).toInstant();

        Instant periodEndExclusive = weekStart
                .plusDays(7)
                .atStartOfDay(zoneId)
                .toInstant();

        // 완료, 사용자 확정 상태인 CBT만 조회
        return reflectionSessionsRepository
                .findAllByUser_IdAndStatusAndUserConfirmedTrueAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                        userId,
                        ReflectionSessionStatus.COMPLETED,
                        periodStart,
                        periodEndExclusive
                );
    }

    // 주간 감정 기록을 감정·요일·시간대별 통계 Map으로 변환
    private Map<String, Object> createWeeklyContent(
            List<EmotionRecords> emotionRecords,
            List<ReflectionSessions> reflectionSessions,
            List<RepeatedEmotionPatternDto> repeatedPatterns,
            ZoneId zoneId
    ) {
        // 감정 코드별 기록 수 집계
        Map<String, Long> emotionCounts = emotionRecords.stream()
                .map(EmotionRecords::getPrimaryEmotionCode)
                .filter(Objects::nonNull)
                .filter(emotionCode -> !emotionCode.isBlank())
                .collect(Collectors.groupingBy(
                        emotionCode -> emotionCode,
                        LinkedHashMap::new,
                        Collectors.counting()
                ));

        // 발생 시각을 사용자 시간대로 변환해 실제 요일별 기록 수 집계
        Map<String, Long> weekdayCounts = emotionRecords.stream()
                .collect(Collectors.groupingBy(
                        emotionRecord -> emotionRecord.getOccurredAt()
                                .atZone(zoneId)
                                .getDayOfWeek()
                                .name(),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));

        // 저장된 시간대(Dawn, Morning, Night 등)별 기록 수 집계
        Map<String, Long> timeBucketCounts = emotionRecords.stream()
                .collect(Collectors.groupingBy(
                        emotionRecord -> emotionRecord.getTimeBucket().name(),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));

        // 감정 강도가 있는 기록만 평균 강도 계산
        OptionalDouble intensityAverage = emotionRecords.stream()
                .map(EmotionRecords::getPrimaryIntensity)
                .filter(Objects::nonNull)
                .mapToInt(Short::intValue)
                .average();

        Double averageIntensity = intensityAverage.isPresent()
                ? intensityAverage.getAsDouble()
                : null;

        // 가장 많이 기록된 감정 코드 조회
        String dominantEmotionCode = emotionCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        // 선택한 주에 최종 확정된 CBT 완료 횟수
        int completedCbtCount = reflectionSessions.size();

        // 도움 점수가 입력된 완료 CBT만 평균 도움 점수 계산
        OptionalDouble helpfulnessAverage = reflectionSessions.stream()
                .map(ReflectionSessions::getHelpfulnessScore)
                .filter(Objects::nonNull)
                .mapToInt(Short::intValue)
                .average();

        Double averageHelpfulnessScore = helpfulnessAverage.isPresent()
                ? helpfulnessAverage.getAsDouble()
                : null;

        // 감정 통계의 근거가 된 감정 기록을 리포트에 함께 저장
        List<WeeklyReportEmotionEvidenceDto> emotionRecordEvidences =
                emotionRecords.stream()
                        .map(WeeklyReportEmotionEvidenceDto::from)
                        .toList();

        // 완료 CBT 통계의 근거가 된 성찰 요약을 리포트에 함께 저장
        List<WeeklyReportCbtEvidenceDto> completedCbtEvidences =
                reflectionSessions.stream()
                        .map(WeeklyReportCbtEvidenceDto::from)
                        .toList();

        // reports.content JSONB에 저장할 통계 구조 생성
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("recordCount", emotionRecords.size());
        content.put("dominantEmotionCode", dominantEmotionCode);
        content.put("averageIntensity", averageIntensity);
        content.put("completedCbtCount", completedCbtCount);
        content.put("averageHelpfulnessScore", averageHelpfulnessScore);
        content.put("emotionCounts", emotionCounts);
        content.put("weekdayCounts", weekdayCounts);
        content.put("timeBucketCounts", timeBucketCounts);
        content.put("repeatedPatterns", repeatedPatterns);
        content.put("emotionRecordEvidences", emotionRecordEvidences);
        content.put("completedCbtEvidences", completedCbtEvidences);

        return content;
    }

    // reports.content JSONB 값을 React 응답 DTO 형식으로 변환
    private WeeklyReportResponseDto toWeeklyReportResponse(
            Reports report
    ) {
        Map<String, Object> content = report.getContent();

        int recordCount = content.get("recordCount") instanceof Number number
                ? number.intValue()
                : 0;

        Double averageIntensity =
                content.get("averageIntensity") instanceof Number number
                        ? number.doubleValue()
                        : null;

        int completedCbtCount =
                content.get("completedCbtCount") instanceof Number number
                        ? number.intValue()
                        : 0;

        Double averageHelpfulnessScore =
                content.get("averageHelpfulnessScore") instanceof Number number
                        ? number.doubleValue()
                        : null;

        String dominantEmotionCode =
                content.get("dominantEmotionCode") instanceof String emotionCode
                        ? emotionCode
                        : null;

        return new WeeklyReportResponseDto(
                report.getId(),
                report.getPeriodStart(),
                report.getPeriodEnd(),
                recordCount,
                dominantEmotionCode,
                averageIntensity,
                completedCbtCount,
                averageHelpfulnessScore,
                toEmotionRecordEvidences(
                        content.get("emotionRecordEvidences")
                ),
                toCompletedCbtEvidences(
                        content.get("completedCbtEvidences")
                ),
                toRepeatedEmotionPatterns(content.get("repeatedPatterns")),
                toLongMap(content.get("emotionCounts")),
                toLongMap(content.get("weekdayCounts")),
                toLongMap(content.get("timeBucketCounts")),
                report.getSourceSnapshotAt()
        );
    }

    // JSONB 안의 숫자 통계 Map을 Map<String, Long>으로 안전하게 변환
    private Map<String, Long> toLongMap(
            Object value
    ) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }

        Map<String, Long> result = new LinkedHashMap<>();

        rawMap.forEach((key, count) -> {
            if (key instanceof String label
                    && count instanceof Number number) {
                result.put(label, number.longValue());
            }
        });

        return result;
    }

    // JSONB에 저장된 감정 기록 근거 목록을 응답 DTO로 변환
    private List<WeeklyReportEmotionEvidenceDto>
    toEmotionRecordEvidences(
            Object value
    ) {
        if (!(value instanceof List<?> rawEvidences)) {
            return List.of();
        }

        List<WeeklyReportEmotionEvidenceDto> evidences =
                new ArrayList<>();

        for (Object rawEvidence : rawEvidences) {
            // 리포트를 방금 생성한 경우에는 DTO 객체 형태로 들어올 수 있음
            if (rawEvidence instanceof WeeklyReportEmotionEvidenceDto evidence){
                evidences.add(evidence);
                continue;
            }

            // DB에서 다시 조회한 JSONB 값은 Map 형태이므로 DTO로 변환
            if (!(rawEvidence instanceof Map<?,?> rawMap)
                    || !(rawMap.get("emotionRecordId")
                    instanceof Number emotionRecordId)) {
                continue;
            }

            Instant occurredAt = null;

            if (rawMap.get("occurredAt")
                    instanceof String occurredAtText) {
                try {
                    occurredAt = Instant.parse(occurredAtText);
                } catch (RuntimeException ignored) {
                    // 날짜 형식이 올바르지 않은 근거 데이터는 null로 변환
                }
            }

            Short primaryIntensity =
                    rawMap.get("primaryIntensity")
                                instanceof Number intensity
                                ? intensity.shortValue()
                                : null;

            String primaryEmotionCode =
                    rawMap.get("primaryEmotionCode")
                            instanceof String emotionCode
                            ? emotionCode
                            : null;

            String situationText =
                    rawMap.get("situationText")
                            instanceof String situation
                            ? situation
                            : null;

            String automaticThought =
                    rawMap.get("automaticThought")
                            instanceof String thought
                            ? thought
                            : null;

            evidences.add(new WeeklyReportEmotionEvidenceDto(
                    emotionRecordId.longValue(),
                    occurredAt,
                    primaryEmotionCode,
                    primaryIntensity,
                    situationText,
                    automaticThought
            ));
        }
        return evidences;
    }

    // JSONB에 저장된 완료 CBT 근거 목록을 응답 DTO로 변환
    private List<WeeklyReportCbtEvidenceDto>
    toCompletedCbtEvidences(
            Object value
    ) {
        if (!(value instanceof List<?> rawEvidences)) {
            return List.of();
        }

        List<WeeklyReportCbtEvidenceDto> evidences =
                new ArrayList<>();

        for (Object rawEvidence : rawEvidences) {
            // 리포트를 방금 생성한 경우에는 DTO 객체 형태로 들어올 수 있음
            if (rawEvidence instanceof WeeklyReportCbtEvidenceDto evidence) {
                evidences.add(evidence);
                continue;
            }

            // DB에서 다시 조회한 JSONB 값은 Map 형태이므로 DTO로 변환
            if (!(rawEvidence instanceof Map<?, ?> rawMap)
                    || !(rawMap.get("sessionId")
                    instanceof Number sessionId)
                    || !(rawMap.get("emotionRecordId")
                    instanceof Number emotionRecordId)) {
                continue;
            }

            String alternativeThoughtText =
                    rawMap.get("alternativeThoughtText")
                            instanceof String alternativeThought
                            ? alternativeThought
                            : null;

            Short helpfulnessScore =
                    rawMap.get("helpfulnessScore")
                            instanceof Number helpfulness
                            ? helpfulness.shortValue()
                            : null;

            evidences.add(new WeeklyReportCbtEvidenceDto(
                    sessionId.longValue(),
                    emotionRecordId.longValue(),
                    alternativeThoughtText,
                    helpfulnessScore
            ));
        }

        return evidences;
    }

    // JSONB에 저장된 반복 패턴 목록을 응답 DTO로 변환
    private List<RepeatedEmotionPatternDto> toRepeatedEmotionPatterns(
            Object value
    ) {
        if (!(value instanceof List<?> rawPatterns)) {
            return List.of();
        }

        List<RepeatedEmotionPatternDto> result = new ArrayList<>();

        for (Object rawPattern : rawPatterns) {
            if (rawPattern instanceof RepeatedEmotionPatternDto pattern) {
                result.add(pattern);
                continue;
            }

            // DB에서 다시 조회한 JSONB 값은 Map 형태이므로 변환
            if (!(rawPattern instanceof Map<?, ?> rawMap)
                    || !(rawMap.get("emotionCode") instanceof String emotionCode)
                    || !(rawMap.get("timeBucket") instanceof String timeBucket)
                    || !(rawMap.get("occurrenceCount") instanceof Number occurrenceCount)
                    || !(rawMap.get("distinctDateCount") instanceof Number distinctDateCount)
                    || !(rawMap.get("observedWeekCount") instanceof Number observedWeekCount)
                    || !(rawMap.get("patternLevel") instanceof String patternLevelName)) {
                continue;
            }

            String weekday = rawMap.get("weekday") instanceof String valueWeekday
                    ? valueWeekday
                    : null;

            try {
                result.add(new RepeatedEmotionPatternDto(
                        emotionCode,
                        weekday,
                        timeBucket,
                        occurrenceCount.longValue(),
                        distinctDateCount.longValue(),
                        observedWeekCount.longValue(),
                        PatternLevel.valueOf(patternLevelName)
                ));
            } catch (IllegalArgumentException ignored) {
                // 알 수 없는 패턴 강도 값은 응답에서 제외
            }
        }

        return result;
    }

    // 선택한 주의 감정 기록으로 주간 리포트 생성 또는 갱신
    @Transactional
    public WeeklyReportResponseDto generateWeeklyReport(
            Long userId,
            LocalDate weekStart
    ) {
        // 월요일 시작일인지 검증
        validateWeekStart(weekStart);

        // 리포트에 연결할 사용자 조회
        Users user = usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));

        // 선택한 주의 월요일~일요일 범위 계산
        LocalDate periodEnd = weekStart.plusDays(6);
        ZoneId zoneId = ZoneId.of(user.getTimezone());

        // 해당 주에 작성된 감정 기록 조회
        List<EmotionRecords> emotionRecords =
                findWeeklyEmotionRecords(userId, weekStart);

        // 선택한 주에 최종 확정된 CBT 세션 조회
        List<ReflectionSessions> reflectionSessions =
                findWeeklyCompletedReflectionSessions(
                        userId,
                        weekStart
                );

        // 기록이 없는 주에는 리포트 생성 불가
        if (emotionRecords.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "선택한 주에 감정 기록이 없어 리포트를 생성할 수 없습니다."
            );
        }

        // 선택한 주를 포함한 최근 8주 감정 기록 조회
        List<EmotionRecords> recentEightWeeksEmotionRecords =
                findRecentEightWeeksEmotionRecords(userId, weekStart);

        // 최근 8주 반복 감정 패턴 계산
        List<RepeatedEmotionPatternDto> repeatedPatterns =
                createRepeatedEmotionPatterns(
                        recentEightWeeksEmotionRecords,
                        zoneId
                );

        // 감정·요일·시간대 통계 생성
        Map<String, Object> content =
                createWeeklyContent(
                        emotionRecords,
                        reflectionSessions,
                        repeatedPatterns,
                        zoneId
                );

        // 같은 사용자·같은 주 리포트가 있으면 갱신, 없으면 새로 저장
        Reports report = reportsRepository
                .findByUser_IdAndReportTypeAndPeriodStartAndPeriodEnd(
                        userId,
                        ReportType.WEEKLY,
                        weekStart,
                        periodEnd
                )
                .map(existingReport -> {
                    existingReport.updateContent(content);
                    return existingReport;
                })
                .orElseGet(() -> reportsRepository.save(
                        Reports.createWeekly(
                                user,
                                weekStart,
                                periodEnd,
                                content
                        )
                ));

        // 방금 생성한 통계를 React 응답 DTO로 변환
        return toWeeklyReportResponse(report);
    }

    // 이미 생성된 선택 주의 리포트 조회
    @Transactional
    public WeeklyReportResponseDto getWeeklyReport(
            Long userId,
            LocalDate weekStart
    ) {
        // 월요일 시작일인지 검증
        validateWeekStart(weekStart);

        LocalDate periodEnd = weekStart.plusDays(6);

        // 본인 리포트 중 선택한 주의 WEEKLY 리포트만 조회
        Reports report = reportsRepository
                .findByUser_IdAndReportTypeAndPeriodStartAndPeriodEnd(
                        userId,
                        ReportType.WEEKLY,
                        weekStart,
                        periodEnd
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "선택한 주에 생성된 주간 리포트가 없습니다."
                ));

        // 저장된 JSONB 통계를 React 응답 DTO로 변환
        return toWeeklyReportResponse(report);
    }
}