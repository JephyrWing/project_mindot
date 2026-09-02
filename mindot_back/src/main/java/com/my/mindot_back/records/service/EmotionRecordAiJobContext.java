// 트랜잭션에서 저장한 감정 기록과 AI 작업 정보를 다음 처리 단계로 전달
package com.my.mindot_back.records.service;

public record EmotionRecordAiJobContext(
        Long emotionRecordId,
        Long aiJobId,
        String rawText
) {
}