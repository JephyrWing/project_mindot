// 회원가입·로그인·Access Token 재발급·로그아웃 인증 API를 처리하는 Controller
package com.my.mindot_back.users.controller;

import com.my.mindot_back.common.auth.AuthOriginValidator;
import com.my.mindot_back.common.auth.RefreshTokenCookieManager;
import com.my.mindot_back.common.config.JwtProperties;
import com.my.mindot_back.common.exception.InvalidRefreshTokenException;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.redis.service.IssuedRefreshToken;
import com.my.mindot_back.redis.service.RefreshTokenService;
import com.my.mindot_back.users.dto.TokenRefreshResponseDto;
import com.my.mindot_back.users.dto.UsersSignupRequestDto;
import com.my.mindot_back.users.dto.UsersSignupResponseDto;
import com.my.mindot_back.users.dto.UsersLoginRequestDto;
import com.my.mindot_back.users.dto.UsersLoginResponseDto;
import com.my.mindot_back.users.service.UsersService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// HTTP 요청을 받는 Controller, 메서드가 반환한 자바 객체를 JSON으로 바꿔서 프론트로 보냄
@RestController
// 모든 api 앞에 /api/auth 붙임
@RequestMapping("/api/auth")
// Lombok이 final 필드를 받는 생성자를 자동으로 만들어 주는 Annotation
@RequiredArgsConstructor
public class UsersController {
    private final UsersService usersService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieManager cookieManager;
    private final AuthOriginValidator originValidator;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    // 회원가입
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UsersSignupResponseDto signup(
            @Valid @RequestBody UsersSignupRequestDto dto
    ){
        return usersService.signup(dto);
    }

    /*
     * 로그인 흐름:
     * 1. UsersService에서 이메일·비밀번호 검증 후 Access Token 발급
     * 2. 기존 Refresh Token 쿠키가 있다면 Redis 세션 폐기 시도
     * 3. 새 Refresh Token을 Redis에 저장하고 HttpOnly 쿠키로 전송
     *
     * JSON body에는 Access Token만 반환
     * Refresh Token은 Set-Cookie 헤더로만 전송
     */
    @PostMapping("/login")
    public ResponseEntity<UsersLoginResponseDto> login(
            @Valid @RequestBody UsersLoginRequestDto dto,
            HttpServletRequest request
    ){
        UsersLoginResponseDto response = usersService.login(dto);

        cookieManager.read(request).ifPresent(this::revokeBestEffort);
        IssuedRefreshToken refreshToken =
                refreshTokenService.issue(response.id());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookieManager.create(refreshToken).toString()
                )
                .body(response);
    }

    /*
     * Access Token 재발급 흐름:
     * 1. Refresh Token 쿠키 요청의 Origin을 검증
     * 2. 쿠키에서 기존 Refresh Token을 읽음
     * 3. Redis에서 해시·만료·재사용 여부를 검증하며 Rotation 수행
     * 4. 새 Access Token은 JSON으로, 새 Refresh Token은 쿠키로 반환
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenRefreshResponseDto> refresh(
            HttpServletRequest request
    ) {
        originValidator.validate(request);

        String oldRefreshToken = cookieManager.read(request)
                .orElseThrow(InvalidRefreshTokenException::new);
        IssuedRefreshToken refreshToken =
                refreshTokenService.rotate(oldRefreshToken);
        String accessToken =
                jwtTokenProvider.createAccessToken(refreshToken.userId());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookieManager.create(refreshToken).toString()
                )
                .body(new TokenRefreshResponseDto(
                        accessToken,
                        "Bearer",
                        jwtProperties.accessTokenExpiration().toSeconds()
                ));
    }

    /*
     * 현재 기기 로그아웃:
     * Redis의 Refresh Token 세션을 폐기,
     * 브라우저의 Refresh Token 쿠키도 삭제
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        originValidator.validate(request);

        cookieManager.read(request).ifPresent(this::revokeForLogout);

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookieManager.delete().toString()
                )
                .build();
    }

    private void revokeBestEffort(String refreshToken) {
        try {
            refreshTokenService.revoke(refreshToken);
        } catch (RuntimeException ignored) {
            // 기존 세션 폐기 실패가 새 로그인을 막지 않게 합니다.
        }
    }

    private void revokeForLogout(String refreshToken) {
        try {
            refreshTokenService.revoke(refreshToken);
        } catch (InvalidRefreshTokenException ignored) {
            // malformed 또는 이미 무효한 쿠키도 로그아웃은 멱등적으로 처리합니다.
        }
    }
}
