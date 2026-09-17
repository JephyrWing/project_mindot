// 관리자 전용 회원 기본 정보 조회 API를 처리하는 Controller
package com.my.mindot_back.admin.controller;

import com.my.mindot_back.admin.dto.AdminUsersPageResponseDto;
import com.my.mindot_back.admin.service.AdminUsersService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUsersController {

    // 관리자 회원 목록 조회 Service
    private final AdminUsersService adminUsersService;

    // 가입일 최신순으로 회원 기본 정보 목록을 페이징 조회
    @GetMapping
    public AdminUsersPageResponseDto getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return adminUsersService.getUsers(page, size);
    }
}
