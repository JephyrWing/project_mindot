// Spring Security의 URL별 접근 권한 설정하는 클래스

package com.my.mindot_back.common.config;

import com.my.mindot_back.common.jwt.JwtAuthenticationFilter;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;

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

                // 인증 정보가 없는 사용자가 보호 API 요청하면 401 반환
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(
                                (request, response, authException) ->
                                        response.sendError(
                                                HttpServletResponse.SC_UNAUTHORIZED
                                        )
                        )
                )
                // JWT 필터 먼저 실행
                // JwtAuthenticationFilter는 Bearer Token을 검증 -> 정상이면 SecurityContext에 userId 등록
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class
                )

                // 회원가입, 로그인, 토큰 재발급: 토큰 없이 접근 가능해야 함
                // 나머지는 JwtAuthenticationFilter의 인증 정보 요구
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
                        // 나머지 API는 정상 Access Token이 있어야 접근 가능
                        .anyRequest().authenticated()
                );
        return http.build();
    }
}
