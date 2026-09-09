// 소셜 로그인 제공자 인가 페이지로 이동할 URL을 프론트에 반환하는 DTO
package com.my.mindot_back.users.dto;

public record OAuthAuthorizationUrlResponseDto(
        String authorizationUrl
) {
}