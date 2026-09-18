// 로그인 사용자의 프로필 조회·수정 HTTP API
package com.my.mindot_back.users.controller;

import com.my.mindot_back.common.auth.RefreshTokenCookieManager;
import com.my.mindot_back.redis.service.RefreshTokenService;
import com.my.mindot_back.users.dto.UsersProfileResponseDto;
import com.my.mindot_back.users.dto.UsersProfileUpdateRequestDto;
import com.my.mindot_back.users.service.UsersService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
// 현재 로그인한 사용자 자신의 프로필 경로
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UsersProfileController {

    private final UsersService usersService;
    private final RefreshTokenCookieManager cookieManager;
    private final RefreshTokenService refreshTokenService;

    // JWT 인증 사용자 프로필 조회
    @GetMapping
    public UsersProfileResponseDto getProfile(
            // JwtAuthenticationFilter가 SecurityContext에 저장한 사용자 ID
            @AuthenticationPrincipal Long userId
    ) {
        return usersService.getProfile(userId);
    }

    // JWT 인증 사용자 닉네임 수정
    @PatchMapping
    public UsersProfileResponseDto updateProfile(
            // URL이나 요청 body가 아닌 JWT 인증 정보에서 사용자 ID 사용
            @AuthenticationPrincipal Long userId,

            // 닉네임의 필수값과 길이 검증
            @Valid @RequestBody UsersProfileUpdateRequestDto dto
    ) {
        return usersService.updateProfile(userId, dto);
    }

    // 로그인한 회원을 탈퇴처리하고 현재 브라우저의 로그인 쿠키를 삭제
    @DeleteMapping
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal Long userId
    ){
        usersService.withdraw(userId);

        try {
            refreshTokenService.revokeAllForUser(userId);
        } catch (RuntimeException exception) {
            // 계정 삭제가 끝난 뒤 Redis 오류로 탈퇴 실패를 표시하지 않음
            log.warn("회원 탈퇴 후 Redis 세션 정리 실패", exception);
        }

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookieManager.delete().toString()
                )
                .build();
    }
}