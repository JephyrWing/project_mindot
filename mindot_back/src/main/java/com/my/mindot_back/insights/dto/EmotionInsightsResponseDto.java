// 확정 감정 기록의 분류별 표본 수와 대표 감정 개수를 전달하는 DTO
package com.my.mindot_back.insights.dto;

import java.util.List;
import java.util.Map;

public record EmotionInsightsResponseDto(
        String groupBy,
        long totalSampleCount,
        List<Group> groups
) {
    // 하나의 시간대, 상황 또는 관계 그룹에 속한 대표 감정 분포
    public record Group(
            String groupCode,
            long sampleCount,
            Map<String, Long> emotionCounts
    ) {
    }
}
