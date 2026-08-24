// yml 파일의 auth.jwt 설정값을 읽는 record
package com.my.mindot_back.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties (
    String secret,
    Duration accessTokenExpiration
){
}
