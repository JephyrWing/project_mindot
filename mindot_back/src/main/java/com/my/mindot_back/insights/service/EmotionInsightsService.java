// 로그인 사용자의 확정 감정 기록을 시간대, 상황, 관계 기준으로 집계하는 Service
package com.my.mindot_back.insights.service;

import com.my.mindot_back.insights.dto.EmotionInsightsResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmotionInsightsService {
    private static final String UNSPECIFIED = "UNSPECIFIED";

    private final JdbcTemplate jdbcTemplate;

    private record CountRow(String groupCode, String emotionCode, long count) {
    }

    // 허용된 분류 컬럼만 선택해 사용자별 COMPLETE 기록을 DB에서 집계
    @Transactional(readOnly = true)
    public EmotionInsightsResponseDto getEmotionInsights(Long userId, String groupBy) {
        String groupColumn = switch (groupBy) {
            case "time" -> "er.time_bucket";
            case "situation" -> "er.context_category";
            case "relationship" -> "er.related_person_type";
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "groupBy는 time, situation, relationship 중 하나여야 합니다."
            );
        };

        // 빈 분류값과 과거 확정 기록의 빈 감정값도 표본에서 누락하지 않음
        String groupExpression = "CASE WHEN " + groupColumn + " IS NULL OR BTRIM(" + groupColumn
                + ") = '' THEN '" + UNSPECIFIED + "' ELSE " + groupColumn + " END";
        String emotionExpression = "CASE WHEN er.primary_emotion_code IS NULL "
                + "OR BTRIM(er.primary_emotion_code) = '' THEN '" + UNSPECIFIED
                + "' ELSE er.primary_emotion_code END";
        String sql = """
                SELECT %s AS group_code,
                       %s AS emotion_code,
                       COUNT(*) AS record_count
                  FROM emotion_records er
                 WHERE er.user_id = ?
                   AND er.completion_status = 'COMPLETE'
                 GROUP BY 1, 2
                 ORDER BY 1, 2
                """.formatted(groupExpression, emotionExpression);

        List<CountRow> rows = jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> new CountRow(
                        resultSet.getString("group_code"),
                        resultSet.getString("emotion_code"),
                        resultSet.getLong("record_count")
                ),
                userId
        );

        Map<String, Map<String, Long>> countsByGroup = new LinkedHashMap<>();
        for (CountRow row : rows) {
            countsByGroup.computeIfAbsent(row.groupCode(), ignored -> new LinkedHashMap<>())
                    .put(row.emotionCode(), row.count());
        }

        List<EmotionInsightsResponseDto.Group> groups = new ArrayList<>();
        long totalSampleCount = 0;
        for (Map.Entry<String, Map<String, Long>> entry : countsByGroup.entrySet()) {
            long sampleCount = entry.getValue()
                    .values()
                    .stream()
                    .mapToLong(Long::longValue)
                    .sum();

            groups.add(new EmotionInsightsResponseDto.Group(
                    entry.getKey(), sampleCount, entry.getValue()
            ));
            totalSampleCount += sampleCount;
        }

        groups.sort(Comparator
                .comparingInt((EmotionInsightsResponseDto.Group group)
                        -> groupOrder(groupBy, group.groupCode()))
                .thenComparing(
                        EmotionInsightsResponseDto.Group::groupCode
                ));
        return new EmotionInsightsResponseDto(
                groupBy,
                totalSampleCount,
                groups);
    }

    // 시간대는 하루 순서대로, 누락 그룹은 다른 분류의 마지막에 표시
    private int groupOrder(String groupBy, String groupCode) {
        if (!"time".equals(groupBy)) {
            return UNSPECIFIED.equals(groupCode) ? 1 : 0;
        }
        return switch (groupCode) {
            case "DAWN" -> 0;
            case "MORNING" -> 1;
            case "AFTERNOON" -> 2;
            case "EVENING" -> 3;
            case "NIGHT" -> 4;
            default -> 5;
        };
    }
}
