// 소셜 인증 결과가 로그인 완료인지 신규 회원 동의 필요 상태인지 전달하는 DTO
package com.my.mindot_back.users.dto;

public record SocialLoginResponseDto(
        boolean signupRequired,
        String signupTicket,
        Long id,
        String email,
        String displayName,
        String accessToken,
        String userRole

) {

    // 기존 회원의 소셜 로그인이 완료된 경우
    public static SocialLoginResponseDto loginCompleted(
            UsersLoginResponseDto loginResponse
    ) {
        return new SocialLoginResponseDto(
                false,
                null,
                loginResponse.id(),
                loginResponse.email(),
                loginResponse.displayName(),
                loginResponse.accessToken(),
                loginResponse.userRole()
        );
    }

    // 소셜 인증은 성공했지만 신규 회원이라 동의가 필요한 경우
    public static SocialLoginResponseDto signupRequired(
            String signupTicket,
            String email,
            String displayName
    ) {
        return new SocialLoginResponseDto(
                true,
                signupTicket,
                null,
                email,
                displayName,
                null,
                null
        );
    }
}