package com.my.mindot_back.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth.refresh-token")
public record RefreshTokenProperties(
        Duration idleExpiration,
        Duration absoluteExpiration
) {
}