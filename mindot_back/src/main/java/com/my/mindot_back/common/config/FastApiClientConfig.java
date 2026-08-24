// FastAPI 내부 서버와 통신할 RestClient를 만드는 설정 클래스
package com.my.mindot_back.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class FastApiClientConfig {

    // yml에서 설정
    private final FastApiProperties fastApiProperties;

    /*
     * FastAPI 전용 HTTP 요청 객체를 Spring Bean으로 등록
     * 이후 Service에서 주입받아 FastAPI를 호출
     */
    @Bean("fastApiRestClient")
    public RestClient fastApiRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(fastApiProperties.baseUrl())
                .build();
    }
}