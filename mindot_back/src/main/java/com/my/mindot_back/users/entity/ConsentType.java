// consent_events.consent_type에 저장할 동의 종류
// TERMS, PRIVACY, AI_ANALYSIS, COUNSELOR_SHARE
package com.my.mindot_back.users.entity;

public enum ConsentType {
    // 이용약관 동의
    TERMS,

    // 개인정보 처리 동의
    PRIVACY,

    // AI가 분석하는 것에 대한 동의
    AI_ANALYSIS,

    // 상담사 공유 기능에 대한 동의
    COUNSELOR_SHARE
}
