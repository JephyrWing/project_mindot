package com.my.mindot_back.redis.service;

import java.time.Instant;

public record IssuedRefreshToken (
        Long userId,
        String value,
        Instant expiresAt
) {
}