// 신규 소셜 회원이 가입 티켓과 필수 동의 결과를 제출하는 요청 DTO
package com.my.mindot_back.users.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SocialSignupCompleteRequestDto(

        @NotBlank
        @Size(max = 512)
        String signupTicket,

        @AssertTrue(message = "이용약관에 동의해야 합니다.")
        boolean termsAgreed,

        @AssertTrue(message = "개인정보 처리에 동의해야 합니다.")
        boolean privacyAgreed,

        @AssertTrue(message = "AI 분석에 동의해야 합니다.")
        boolean aiAnalysisAgreed
) {
}