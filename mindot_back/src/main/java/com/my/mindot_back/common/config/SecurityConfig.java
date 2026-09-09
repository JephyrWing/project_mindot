// Spring Security의 URL별 접근 권한 설정하는 클래스

package com.my.mindot_back.common.config;

import com.my.mindot_back.common.jwt.JwtAuthenticationFilter;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final AuthWebProperties authWebProperties;
    private final UsersRepository usersRepository;

    // HTTP 요청이 Controller에 도달하기 전에 어떤 요청을 허용, 차단할지 결정하는 필터 묶음
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())

                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

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

                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(
                                (request, response, authException) ->
                                        writeSecurityError(
                                                response,
                                                HttpStatus.UNAUTHORIZED,
                                                "인증이 필요합니다."
                                        )
                        )
                        .accessDeniedHandler(
                                (request, response, accessDeniedException) ->
                                        writeSecurityError(
                                                response,
                                                HttpStatus.FORBIDDEN,
                                                "접근 권한이 없습니다."
                                        )
                        )
                )

                // JWT와 DB의 현재 계정 상태·역할을 검증한 뒤 SecurityContext에 등록
                .addFilterBefore(
                        new JwtAuthenticationFilter(
                                jwtTokenProvider,
                                usersRepository
                        ),
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
                                "/api/auth/logout",
                                "/api/auth/oauth/kakao",
                                "/api/auth/oauth/google"
                        ).permitAll()

                        // 소셜 로그인 시작 URL 요청은 로그인 전에도 허용
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/auth/oauth/kakao/authorize",
                                "/api/auth/oauth/google/authorize"
                        ).permitAll()

                        // DB의 실제 ROLE_USER 또는 ROLE_ADMIN을 가진 로그인 사용자만 접근 가능
                        .requestMatchers(
                                "/api/records/**",
                                "/api/reflections/**",
                                "/api/reports/**"
                        ).hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // 나머지 API는 정상 Access Token이 있어야 접근 가능
                        .anyRequest().authenticated()
                );
        return http.build();
    }

    // Spring Security 단계의 401, 403도 프론트 공통 오류 JSON으로 반환
    private void writeSecurityError(
            HttpServletResponse response,
            HttpStatus status,
            String message
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        response.getWriter().write(
                "{\"status\":" + status.value()
                        + ",\"message\":\"" + message + "\"}"
        );
    }

    /*
     * 프론트엔드가 Authorization 헤더를 보내고,
     * Refresh Token 쿠키를 포함한 요청을 보낼 수 있도록 CORS를 설정
    */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(authWebProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()
        ));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE
        ));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
