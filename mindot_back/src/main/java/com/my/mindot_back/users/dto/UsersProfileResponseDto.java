// 로그인 사용자의 프로필 조회·수정 결과 DTO
package com.my.mindot_back.users.dto;

import com.my.mindot_back.users.entity.Users;

import java.time.Instant;

public record UsersProfileResponseDto(
        // 사용자 DB 식별자
        Long id,

        // 로그인과 계정 식별에 사용하는 이메일
        String email,

        // 화면에 표시하는 사용자 닉네임
        String displayName,

        // 사용자 기준 시간대
        String timezone,

        // 사용자 언어 및 지역 설정
        String locale,

        // 회원가입 시각
        Instant createdAt
) {

    public static UsersProfileResponseDto from(Users user) {
        return new UsersProfileResponseDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getTimezone(),
                user.getLocale(),
                user.getCreatedAt()
        );
    }
}