// 답변 저장 후, 다음 질문 생성 FastAPI 요청에 필요한 값 전달
package com.my.mindot_back.records.service;

import com.my.mindot_back.records.dto.ai.FastApiCbtTurnRequestDto;

public record ReflectionSessionTurnAiContext(
        Long sessionId,
        Long aiJobId,
        FastApiCbtTurnRequestDto request
) {
}