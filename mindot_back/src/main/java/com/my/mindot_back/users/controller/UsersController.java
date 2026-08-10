// 프론트엔드의 HTTP 요청을 받는 Controller
// POST /api/auth/signup 요청을 받아 UsersService의 회원가입 기능 호출
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

    // 로그인
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

/*
프론트 JSON
→ Controller가 DTO로 받음
→ Controller가 DTO를 Service에 전달
→ Service가 DB 저장 후 응답 DTO를 반환
→ Controller가 그 응답 DTO를 프론트에 반환
 */
