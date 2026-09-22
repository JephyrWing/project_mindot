// 감정 기록에서 누락된 구조화 항목의 보완 질문 응답을 정의하는 DTO
package com.my.mindot_back.records.dto;

import java.util.List;

public record EmotionRecordMissingQuestionsResponseDto(
        Long emotionRecordId,
        String completionStatus,
        List<MissingQuestion> questions
) {
    public record MissingQuestion(
            String fieldName,
            String question,
            boolean required
    ) {
    }
}
