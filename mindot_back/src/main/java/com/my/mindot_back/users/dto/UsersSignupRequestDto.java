// Data Transfer Object
// 프론트가 회원가입 요청 시 Spring 서버로 보내는 데이터 형식

package com.my.mindot_back.users.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * record 사용 이유:
 * 회원가입 요청 DTO는 프론트엔드에서 받은 값을
 * Controller → Service로 전달하는 용도
 *
 * 값을 수정할 필요가 없으므로 getter, 생성자 등을
 * Java가 자동 생성하는 record를 사용
 */

public record UsersSignupRequestDto (
    @NotBlank // 빈문자열, null 불가
    @Email
    @Size(max = 255)
    String email,

    // 사용자가 입력한 원문 비밀번호
    // Service에서 BCrypt 해시로 변환 후 저장
    @NotBlank
    @Size(min = 8, max = 72)
    String password,

    @NotBlank
    @Size(max = 80)
    String displayName,

    // 이용약관 동의 여부
    // consent_events에 TERMS / GRANTED 이벤트로 저장
    @AssertTrue
    boolean termsAgreed,

    // 개인정보 처리 동의 여부
    // consent_events에 PRIVACY / GRANTED 이벤트로 저장
    @AssertTrue
    boolean privacyAgreed,

    // AI 분석 동의 여부
    // consent_events에 AI_ANALYSIS / GRANTED 이벤트로 저장
    @AssertTrue
    boolean aiAnalysisAgreed
){


}
