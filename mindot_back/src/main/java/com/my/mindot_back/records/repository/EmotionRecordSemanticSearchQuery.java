// 감정 기록 의미 검색에 필요한 사용자·벡터·필터 조건을 Repository에 전달
package com.my.mindot_back.records.repository;

import java.time.Instant;

public record EmotionRecordSemanticSearchQuery(
        Long userId,
        String embeddedQueryString,
        Instant periodStart,
        Instant periodEndExclusive,
        String emotionCode,
        String contextCategory,
        double threshold
) {
}