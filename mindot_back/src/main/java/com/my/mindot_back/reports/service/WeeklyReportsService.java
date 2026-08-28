// 선택한 주의 감정 기록을 집계해 주간 리포트를 생성·갱신하는 Service
package com.my.mindot_back.reports.service;

import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.reports.dto.WeeklyReportResponseDto;
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
import java.util.*;
import java.util.stream.Collectors;

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

        // 감정·요일·시간대 통계 생성
        Map<String, Object> content =
                createWeeklyContent(
                        emotionRecords,
                        reflectionSessions,
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