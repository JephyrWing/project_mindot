// 감정 기록 검색 임베딩 작업 정보를 트랜잭션 밖의 외부 API 호출 단계로 전달
package com.my.mindot_back.records.service;

public record EmotionRecordSearchEmbeddingContext(
        Long emotionRecordId,
        Long aiJobId,
        String rawText,
        boolean dispatch
) {
}