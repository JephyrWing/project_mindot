// CBT 완료 후 트랜잭션 밖에서 임베딩 API를 호출하기 위한 입력값
package com.my.mindot_back.records.service;

public record ReflectionSessionEmbeddingContext(
        Long sessionId,
        Long aiJobId,
        String contextEmbeddingText,
        String thoughtAwareEmbeddingText
) {
}
