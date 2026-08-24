// AI 작업이 수행하는 구체적인 종류를 구분하는 enum
package com.my.mindot_back.ai.entity;

public enum AiJobOperation {
    // 감정 기록 원문 구조화 작업
    STRUCTURE,

    // CBT 성찰에 사용할 다음 질문 생성 작업
    QUESTION,

    // 인지왜곡 유형 분류 작업
    DISTORTION_CLASSIFY,

    // 검색에 사용할 임베딩 벡터 생성 작업
    EMBED,

    // 기존 정보 검색해 AI 응답에 활용하는 작업
    PATTERN_RAG,

    // 리포트 생성 작업
    REPORT_GENERATE,

    // 위험 신호 검사
    SAFETY_CHECK
}
