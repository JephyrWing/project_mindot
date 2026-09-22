// 사용자 시간대를 기준으로 월간 감정 흐름과 CBT 요약을 생성·조회
package com.my.mindot_back.reports.service;

import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.reports.dto.MonthlyIntensityTrend;
import com.my.mindot_back.reports.dto.MonthlyReportDailyTrendDto;
import com.my.mindot_back.reports.dto.MonthlyReportResponseDto;
import com.my.mindot_back.reports.entity.ReportType;
import com.my.mindot_back.reports.entity.Reports;
import com.my.mindot_back.reports.repository.ReportsRepository;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MonthlyReportsService {

    // 초반·후반 평균 강도 차이가 0.5 이하면 비슷한 흐름으로 판단
    private static final double STABLE_INTENSITY_DIFFERENCE = 0.5;

    private final ReportsRepository reportsRepository;
    private final EmotionRecordsRepository emotionRecordsRepository;
    private final ReflectionSessionsRepository reflectionSessionsRepository;
    private final UsersRepository usersRepository;

    // 요청한 월 값이 있는지 확인
    private void validateMonth(YearMonth month) {
        if (month == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "조회할 월을 입력해야 합니다."
            );
        }
    }

    // 사용자 시간대를 기준으로 해당 월의 감정 기록 조회
    private List<EmotionRecords> findMonthlyEmotionRecords(
            Long userId,
            YearMonth month,
            ZoneId zoneId
    ) {
        LocalDate periodStart = month.atDay(1);
        Instant periodStartInstant = periodStart
                .atStartOfDay(zoneId)
                .toInstant();

        // 다음 달 1일 00:00 미만으로 조회해 월 마지막 날 전체를 포함
        Instant periodEndExclusive = month
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(zoneId)
                .toInstant();

        return emotionRecordsRepository
                .findAllByUser_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(
                        userId,
                        periodStartInstant,
                        periodEndExclusive
                );
    }

    // 사용자 시간대를 기준으로 해당 월에 완료·확정한 CBT 조회
    private List<ReflectionSessions> findMonthlyCompletedReflections(
            Long userId,
            YearMonth month,
            ZoneId zoneId
    ) {
        Instant periodStartInstant = month
                .atDay(1)
                .atStartOfDay(zoneId)
                .toInstant();

        Instant periodEndExclusive = month
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(zoneId)
                .toInstant();

        return reflectionSessionsRepository
                .findAllByUser_IdAndStatusAndUserConfirmedTrueAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                        userId,
                        ReflectionSessionStatus.COMPLETED,
                        periodStartInstant,
                        periodEndExclusive
                );
    }

    // 감정 코드 또는 상황 분류처럼 문자열 속성별 기록 수 집계
    private Map<String, Long> countRecordsBy(
            List<EmotionRecords> records,
            Function<EmotionRecords, String> classifier
    ) {
        return records.stream()
                .map(classifier)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(Collectors.groupingBy(
                        Function.identity(),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));
    }

    // 강도가 입력된 감정 기록만 평균 계산
    private Double averageIntensity(List<EmotionRecords> records) {
        OptionalDouble average = records.stream()
                .map(EmotionRecords::getPrimaryIntensity)
                .filter(Objects::nonNull)
                .mapToInt(Short::intValue)
                .average();

        return average.isPresent() ? average.getAsDouble() : null;
    }

    // 도움 점수가 입력된 완료 CBT만 평균 계산
    private Double averageHelpfulness(
            List<ReflectionSessions> reflectionSessions
    ) {
        OptionalDouble average = reflectionSessions.stream()
                .map(ReflectionSessions::getHelpfulnessScore)
                .filter(Objects::nonNull)
                .mapToInt(Short::intValue)
                .average();

        return average.isPresent() ? average.getAsDouble() : null;
    }

    // 횟수가 가장 많은 분류를 선택하고, 동률이면 코드 이름순으로 선택
    private String mostFrequentValue(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .sorted(
                        Map.Entry.<String, Long>comparingByValue()
                                .reversed()
                                .thenComparing(Map.Entry.comparingByKey())
                )
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    // 월의 모든 날짜를 포함하는 일별 그래프 데이터 생성
    private List<MonthlyReportDailyTrendDto> createDailyTrends(
            List<EmotionRecords> records,
            YearMonth month,
            ZoneId zoneId
    ) {
        Map<LocalDate, List<EmotionRecords>> recordsByDate =
                records.stream()
                        .collect(Collectors.groupingBy(
                                record -> record.getOccurredAt()
                                        .atZone(zoneId)
                                        .toLocalDate(),
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));

        List<MonthlyReportDailyTrendDto> dailyTrends =
                new ArrayList<>();

        for (
                LocalDate date = month.atDay(1);
                !date.isAfter(month.atEndOfMonth());
                date = date.plusDays(1)
        ) {
            List<EmotionRecords> dailyRecords =
                    recordsByDate.getOrDefault(date, List.of());

            Map<String, Long> dailyEmotionCounts =
                    countRecordsBy(
                            dailyRecords,
                            EmotionRecords::getPrimaryEmotionCode
                    );

            dailyTrends.add(new MonthlyReportDailyTrendDto(
                    date,
                    dailyRecords.size(),
                    averageIntensity(dailyRecords),
                    mostFrequentValue(dailyEmotionCounts)
            ));
        }

        return dailyTrends;
    }

    // 월 초반 또는 후반에 해당하는 감정 기록의 평균 강도 계산
    private Double averageIntensityForHalf(
            List<EmotionRecords> records,
            YearMonth month,
            ZoneId zoneId,
            boolean firstHalf
    ) {
        int firstHalfLastDay = month.lengthOfMonth() / 2;

        List<EmotionRecords> halfRecords = records.stream()
                .filter(record -> {
                    int day = record.getOccurredAt()
                            .atZone(zoneId)
                            .getDayOfMonth();

                    return firstHalf
                            ? day <= firstHalfLastDay
                            : day > firstHalfLastDay;
                })
                .toList();

        return averageIntensity(halfRecords);
    }

    // 월 초반과 후반의 평균 감정 강도 차이를 흐름 상태로 변환
    private MonthlyIntensityTrend calculateIntensityTrend(
            Double firstHalfAverage,
            Double secondHalfAverage
    ) {
        if (firstHalfAverage == null || secondHalfAverage == null) {
            return MonthlyIntensityTrend.INSUFFICIENT_DATA;
        }

        double difference = secondHalfAverage - firstHalfAverage;

        if (difference > STABLE_INTENSITY_DIFFERENCE) {
            return MonthlyIntensityTrend.INCREASED;
        }

        if (difference < -STABLE_INTENSITY_DIFFERENCE) {
            return MonthlyIntensityTrend.DECREASED;
        }

        return MonthlyIntensityTrend.STABLE;
    }

    // 집계 결과를 외부 AI 호출 없이 설명 가능한 월간 요약 문장으로 변환
    private String createSummaryText(
            int recordCount,
            String dominantEmotionCode,
            String mostFrequentContextCategory,
            com.my.mindot_back.reports.dto.MonthlyEmotionCompositionDto composition,
            int completedCbtCount
    ) {
        if (recordCount == 0) {
            return "이번 달에는 작성된 감정 기록이 없으며, 완료한 CBT는 "
                    + completedCbtCount
                    + "회입니다.";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("이번 달에는 감정 기록 ")
                .append(recordCount)
                .append("건을 작성했습니다.");

        if (dominantEmotionCode != null) {
            summary.append(" 가장 많이 기록된 감정은 ")
                    .append(ReportEmotionData.label(dominantEmotionCode))
                    .append("입니다.");
        }

        if (mostFrequentContextCategory != null) {
            summary.append(" 가장 자주 나타난 상황은 ")
                    .append(ReportEmotionData.contextLabel(mostFrequentContextCategory))
                    .append("입니다.");
        }

        summary.append(" ")
                .append("월 초반 ").append(composition.halves().get(0).recordCount())
                .append("건, 월 후반 ").append(composition.halves().get(1).recordCount())
                .append("건이며, 각 기간의 감정 구성은 기록 건수에 대한 비율로 표시합니다.")
                .append(" 완료한 CBT는 ")
                .append(completedCbtCount)
                .append("회입니다.");

        return summary.toString();
    }

    // 월간 집계 결과를 reports.content JSONB에 저장할 구조로 생성
    private Map<String, Object> createMonthlyContent(
            List<EmotionRecords> emotionRecords,
            List<ReflectionSessions> reflectionSessions,
            YearMonth month,
            ZoneId zoneId
    ) {
        Map<String, Long> emotionCounts = countRecordsBy(
                emotionRecords,
                EmotionRecords::getPrimaryEmotionCode
        );

        Map<String, Long> contextCategoryCounts = countRecordsBy(
                emotionRecords,
                EmotionRecords::getContextCategory
        );

        String dominantEmotionCode =
                mostFrequentValue(emotionCounts);

        String mostFrequentContextCategory =
                mostFrequentValue(contextCategoryCounts);

        Double firstHalfAverageIntensity =
                averageIntensityForHalf(
                        emotionRecords,
                        month,
                        zoneId,
                        true
                );

        Double secondHalfAverageIntensity =
                averageIntensityForHalf(
                        emotionRecords,
                        month,
                        zoneId,
                        false
                );

        MonthlyIntensityTrend intensityTrend =
                calculateIntensityTrend(
                        firstHalfAverageIntensity,
                        secondHalfAverageIntensity
                );

        int completedCbtCount = reflectionSessions.size();
        var composition = ReportEmotionData.monthly(emotionRecords, month, zoneId);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("emotionComposition", composition);
        content.put("recordCount", emotionRecords.size());
        content.put("dominantEmotionCode", dominantEmotionCode);
        content.put(
                "averageIntensity",
                averageIntensity(emotionRecords)
        );
        content.put("completedCbtCount", completedCbtCount);
        content.put(
                "averageHelpfulnessScore",
                averageHelpfulness(reflectionSessions)
        );
        content.put(
                "firstHalfAverageIntensity",
                firstHalfAverageIntensity
        );
        content.put(
                "secondHalfAverageIntensity",
                secondHalfAverageIntensity
        );
        content.put("intensityTrend", intensityTrend.name());
        content.put(
                "mostFrequentContextCategory",
                mostFrequentContextCategory
        );
        content.put(
                "summaryText",
                createSummaryText(
                        emotionRecords.size(),
                        dominantEmotionCode,
                        mostFrequentContextCategory,
                        composition,
                        completedCbtCount
                )
        );
        content.put(
                "dailyTrends",
                createDailyTrends(
                        emotionRecords,
                        month,
                        zoneId
                )
        );
        content.put("emotionCounts", emotionCounts);
        content.put(
                "contextCategoryCounts",
                contextCategoryCounts
        );

        return content;
    }

    // JSONB 숫자를 int로 안전하게 변환
    private int toInt(Object value) {
        return value instanceof Number number
                ? number.intValue()
                : 0;
    }

    // JSONB 숫자를 Double로 안전하게 변환
    private Double toDouble(Object value) {
        return value instanceof Number number
                ? number.doubleValue()
                : null;
    }

    // JSONB 문자열을 null 허용 형태로 변환
    private String toStringValue(Object value) {
        return value instanceof String stringValue
                ? stringValue
                : null;
    }

    // JSONB 객체를 Map<String, Long>으로 안전하게 변환
    private Map<String, Long> toLongMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }

        Map<String, Long> result = new LinkedHashMap<>();

        rawMap.forEach((key, count) -> {
            if (key instanceof String stringKey
                    && count instanceof Number number) {
                result.put(stringKey, number.longValue());
            }
        });

        return result;
    }

    // 저장된 흐름 문자열을 enum으로 안전하게 변환
    private MonthlyIntensityTrend toIntensityTrend(Object value) {
        if (!(value instanceof String trendName)) {
            return MonthlyIntensityTrend.INSUFFICIENT_DATA;
        }

        try {
            return MonthlyIntensityTrend.valueOf(trendName);
        } catch (IllegalArgumentException exception) {
            return MonthlyIntensityTrend.INSUFFICIENT_DATA;
        }
    }

    // JSONB에 문자열 또는 [연, 월, 일] 배열로 저장된 날짜를 복원
    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }

        if (value instanceof String dateText) {
            try {
                return LocalDate.parse(dateText);
            } catch (RuntimeException exception) {
                return null;
            }
        }

        if (value instanceof List<?> dateParts
                && dateParts.size() == 3
                && dateParts.get(0) instanceof Number year
                && dateParts.get(1) instanceof Number month
                && dateParts.get(2) instanceof Number day) {
            try {
                return LocalDate.of(
                        year.intValue(),
                        month.intValue(),
                        day.intValue()
                );
            } catch (RuntimeException exception) {
                return null;
            }
        }

        return null;
    }

    // JSONB에 저장된 날짜별 집계를 응답 DTO 목록으로 복원
    private List<MonthlyReportDailyTrendDto> toDailyTrends(
            Object value
    ) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }

        List<MonthlyReportDailyTrendDto> result =
                new ArrayList<>();

        for (Object item : rawList) {
            // 리포트 생성 직후 아직 Map으로 역직렬화되지 않은 경우
            if (item instanceof MonthlyReportDailyTrendDto dailyTrend) {
                result.add(dailyTrend);
                continue;
            }

            if (!(item instanceof Map<?, ?> rawMap)) {
                continue;
            }

            LocalDate date = toLocalDate(rawMap.get("date"));

            if (date == null) {
                // 날짜 형식이 깨진 항목만 응답에서 제외
                continue;
            }

            result.add(new MonthlyReportDailyTrendDto(
                    date,
                    rawMap.get("recordCount") instanceof Number number
                            ? number.longValue()
                            : 0L,
                    toDouble(rawMap.get("averageIntensity")),
                    toStringValue(rawMap.get("dominantEmotionCode"))
            ));
        }

        return result;
    }

    // reports.content JSONB를 월간 리포트 응답 DTO로 변환
    private MonthlyReportResponseDto toMonthlyReportResponse(
            Reports report
    ) {
        Map<String, Object> content = report.getContent();

        return new MonthlyReportResponseDto(
                report.getId(),
                report.getPeriodStart(),
                report.getPeriodEnd(),
                toInt(content.get("recordCount")),
                toStringValue(content.get("dominantEmotionCode")),
                toDouble(content.get("averageIntensity")),
                toInt(content.get("completedCbtCount")),
                toDouble(content.get("averageHelpfulnessScore")),
                toDouble(content.get("firstHalfAverageIntensity")),
                toDouble(content.get("secondHalfAverageIntensity")),
                toIntensityTrend(content.get("intensityTrend")),
                toStringValue(
                        content.get("mostFrequentContextCategory")
                ),
                toStringValue(content.get("summaryText")),
                toDailyTrends(content.get("dailyTrends")),
                toLongMap(content.get("emotionCounts")),
                toLongMap(content.get("contextCategoryCounts")),
                report.getSourceSnapshotAt(),
                ReportEmotionData.restore(content.get("emotionComposition"))
        );
    }

    // 선택한 달의 최신 감정 기록과 완료 CBT로 월간 리포트 생성 또는 갱신
    @Transactional
    public MonthlyReportResponseDto generateMonthlyReport(
            Long userId,
            YearMonth month
    ) {
        validateMonth(month);

        // 동일 사용자의 동시 생성 요청을 직렬화해 중복 INSERT 방지
        Users user = usersRepository.findLockedById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));

        ZoneId zoneId = ZoneId.of(user.getTimezone());
        LocalDate periodStart = month.atDay(1);
        LocalDate periodEnd = month.atEndOfMonth();

        List<EmotionRecords> emotionRecords =
                findMonthlyEmotionRecords(
                        userId,
                        month,
                        zoneId
                );

        List<ReflectionSessions> reflectionSessions =
                findMonthlyCompletedReflections(
                        userId,
                        month,
                        zoneId
                );

        // 감정 기록과 완료 CBT가 모두 없는 달은 빈 리포트를 저장하지 않음
        if (emotionRecords.isEmpty()
                && reflectionSessions.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "선택한 달에 감정 기록과 완료된 CBT 성찰이 없습니다."
            );
        }

        Map<String, Object> content = createMonthlyContent(
                emotionRecords,
                reflectionSessions,
                month,
                zoneId
        );

        // 같은 월의 리포트가 있으면 갱신하고, 없으면 새로 생성
        Reports report = reportsRepository
                .findByUser_IdAndReportTypeAndPeriodStartAndPeriodEnd(
                        userId,
                        ReportType.MONTHLY,
                        periodStart,
                        periodEnd
                )
                .map(existingReport -> {
                    existingReport.updateContent(content);
                    return existingReport;
                })
                .orElseGet(() -> reportsRepository.save(
                        Reports.createMonthly(
                                user,
                                periodStart,
                                periodEnd,
                                content
                        )
                ));

        return toMonthlyReportResponse(report);
    }

    // 이미 생성된 선택 달의 월간 리포트 조회
    @Transactional
    public MonthlyReportResponseDto getMonthlyReport(
            Long userId,
            YearMonth month
    ) {
        validateMonth(month);

        LocalDate periodStart = month.atDay(1);
        LocalDate periodEnd = month.atEndOfMonth();

        Reports report = reportsRepository
                .findByUser_IdAndReportTypeAndPeriodStartAndPeriodEnd(
                        userId,
                        ReportType.MONTHLY,
                        periodStart,
                        periodEnd
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "선택한 달에 생성된 월간 리포트가 없습니다."
                ));

        var composition = ReportEmotionData.restore(report.getContent().get("emotionComposition"));
        // Legacy snapshots must be rebuilt from all owned records, never interpreted as zero.
        if (composition == null || composition.version() != ReportEmotionData.VERSION
                || composition.days().size() != month.lengthOfMonth() || composition.halves().size() != 2) {
            return generateMonthlyReport(userId, month);
        }
        return toMonthlyReportResponse(report);
    }
}
