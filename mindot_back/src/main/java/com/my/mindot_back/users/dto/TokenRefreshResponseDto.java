package com.my.mindot_back.users.dto;

public record TokenRefreshResponseDto(
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {
}
