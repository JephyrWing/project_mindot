// 저장된 패턴 피드백과 갱신 시각의 API 응답 계약
package com.my.mindot_back.reports.dto;

import com.my.mindot_back.reports.entity.PatternFeedback;
import com.my.mindot_back.reports.entity.PersonalPattern;

import java.time.Instant;

public record PatternFeedbackResponseDto(
        Long patternId, PatternFeedback feedback, Instant feedbackAt
) {
    public static PatternFeedbackResponseDto from(PersonalPattern pattern) {
        return new PatternFeedbackResponseDto(
                pattern.getId(), pattern.getFeedback(), pattern.getFeedbackAt()
        );
    }
}
