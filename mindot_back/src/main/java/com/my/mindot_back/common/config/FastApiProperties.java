// application.yml의 ai.fast-api 설정값을 읽는 record
package com.my.mindot_back.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;


// FastAPI 내부 서버 주소 설정
@ConfigurationProperties(prefix = "ai.fast-api")
public record FastApiProperties(

        // FastAPI 서버의 기본 주소
        String baseUrl
) {
}