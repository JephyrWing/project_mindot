// 로그인 사용자의 프로필 조회·수정 HTTP API
package com.my.mindot_back.users.controller;

import com.my.mindot_back.users.dto.UsersProfileResponseDto;
import com.my.mindot_back.users.dto.UsersProfileUpdateRequestDto;
import com.my.mindot_back.users.service.UsersService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
// 현재 로그인한 사용자 자신의 프로필 경로
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UsersProfileController {

    private final UsersService usersService;

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
}