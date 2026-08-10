// Spring Security의 URL별 접근 권한 설정하는 클래스

package com.my.mindot_back.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    // HTTP 요청이 Controller에 도달하기 전에 어떤 요청을 허용, 차단할지 결정하는 필터 묶음
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // html form 로그인 사용 x
                // React가 JSON 요청으로 로그인 API 호출
                .formLogin(form -> form.disable())

                // HTTP Basic 인증 사용 x
                .httpBasic(basic -> basic.disable())

                // jwt 방식 -> 서버 세션을 만들지 않도록 STATELESS로 설정
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                // 회원가입, 로그인, 토큰 재발급, 로그아웃: 공개
                // 나머지는 authenticated()로 보호
                .authorizeHttpRequests(auth -> auth
                        // service에서 발생한 오류를 spring이 JSON 응답으로 바꾸는 내부 경로
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/signup",
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/logout"
                        ).permitAll()
                        .anyRequest().authenticated()
                );
        return http.build();
    }
}
