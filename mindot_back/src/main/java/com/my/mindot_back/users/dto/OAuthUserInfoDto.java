// 카카오·구글에서 검증한 소셜 로그인 사용자 정보를 공통 형식으로 전달하는 DTO
package com.my.mindot_back.users.dto;

public record OAuthUserInfoDto(
        String providerUserId,
        String email,
        String displayName
) {
}