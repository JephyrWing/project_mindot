// 패턴 도움 여부 저장 요청에서 허용되는 두 선택지만 받는 DTO
package com.my.mindot_back.reports.dto;

import com.my.mindot_back.reports.entity.PatternFeedback;
import jakarta.validation.constraints.NotNull;

public record PatternFeedbackRequestDto(@NotNull PatternFeedback feedback) {
}
