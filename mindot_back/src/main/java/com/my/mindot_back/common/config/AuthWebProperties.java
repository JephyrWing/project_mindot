package com.my.mindot_back.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "auth.web")
public record AuthWebProperties(
        List<String> allowedOrigins
) {
    public AuthWebProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        if (allowedOrigins.isEmpty() || allowedOrigins.contains("*")) {
            throw new IllegalArgumentException(
                    "auth.web.allowed-origins에는 명시적인 Origin이 필요합니다."
            );
        }
    }
}
