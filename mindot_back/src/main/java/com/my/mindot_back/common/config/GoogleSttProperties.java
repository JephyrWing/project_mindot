package com.my.mindot_back.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// application.yml의 stt.google 설정값을 읽는 객체
@ConfigurationProperties(prefix = "stt.google")
public record GoogleSttProperties(
        String projectId,
        String location,
        String languageCode,
        String model
) {
}