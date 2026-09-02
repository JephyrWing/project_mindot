// 첫 질문 생성 전 DB에 저장된 세션·AI 작업 정보와 FastAPI 요청값 전달
package com.my.mindot_back.records.service;

import com.my.mindot_back.records.dto.ai.FastApiCbtStartRequestDto;

public record ReflectionSessionStartAiContext(
        Long sessionId,
        Long aiJobId,
        FastApiCbtStartRequestDto request
) {
}