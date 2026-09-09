// OAuth 로그인 시작·콜백 state 검증용 HttpOnly 쿠키 설정을 읽는 객체
package com.my.mindot_back.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth.oauth-state-cookie")
public record OAuthStateCookieProperties(
        String path,
        boolean secure,
        String sameSite,
        Duration maxAge
) {
}