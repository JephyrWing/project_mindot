// 프론트엔드의 HTTP 요청을 받는 Controller
// POST /api/auth/signup 요청을 받아 UsersService의 회원가입 기능 호출
package com.my.mindot_back.users.controller;

import com.my.mindot_back.users.dto.UsersSignupRequestDto;
import com.my.mindot_back.users.dto.UsersSignupResponseDto;
import com.my.mindot_back.users.dto.UsersLoginRequestDto;
import com.my.mindot_back.users.dto.UsersLoginResponseDto;
import com.my.mindot_back.users.service.UsersService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// HTTP 요청을 받는 Controller, 메서드가 반환한 자바 객체를 JSON으로 바꿔서 프론트로 보냄
@RestController
// 모든 api 앞에 /api/auth 붙임
@RequestMapping("/api/auth")
// Lombok이 final 필드를 받는 생성자를 자동으로 만들어 주는 Annotation
@RequiredArgsConstructor
public class UsersController {
    private final UsersService usersService;

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
    public UsersLoginResponseDto login(
            @Valid @RequestBody UsersLoginRequestDto dto
    ){
        return usersService.login(dto);
    }
}

/*
프론트 JSON
→ Controller가 DTO로 받음
→ Controller가 DTO를 Service에 전달
→ Service가 DB 저장 후 응답 DTO를 반환
→ Controller가 그 응답 DTO를 프론트에 반환
 */
