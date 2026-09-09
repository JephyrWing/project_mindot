// 구글 OAuth 환경변수를 application.yml에서 읽는 설정 객체
package com.my.mindot_back.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oauth.google")
public record GoogleOAuthProperties(
        String clientId,
        String clientSecret,
        String redirectUri
) {
}