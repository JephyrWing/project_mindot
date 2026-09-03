// 관리자가 회원 기본 정보와 안전 신호 횟수를 조회할 때 사용하는 응답 DTO
package com.my.mindot_back.admin.dto;

import com.my.mindot_back.users.entity.AccountStatus;
import com.my.mindot_back.users.entity.UserRole;
import com.my.mindot_back.users.entity.Users;

import java.time.Instant;

public record AdminUsersListResponseDto(
        Long userId,
        String email,
        String displayName,
        AccountStatus status,
        UserRole userRole,
        Long safetyEventCount,
        Instant createdAt
) {
    // Users Entity와 회원별 안전 신호 횟수를 관리자 회원 목록 응답 DTO로 변환
    public static AdminUsersListResponseDto from(
            Users user,
            Long safetyEventCount
    ) {
        return new AdminUsersListResponseDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                user.getUserRole(),
                safetyEventCount,
                user.getCreatedAt()
        );
    }
}