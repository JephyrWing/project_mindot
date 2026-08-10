// 회원가입 성공 시 프론트에 돌려줄 응답 DTO
package com.my.mindot_back.users.dto;

import com.my.mindot_back.users.entity.Users;

import java.time.Instant;

/* record 사용 이유
 * 이 DTO는 응답값을 담아 전달만 하며 수정할 필요X
 */
public record UsersSignupResponseDto (
    // DB에 저장된 사용자 ID
    Long id,

    // 로그인에 사용하는 이메일
    String email,

    // 이름
    String displayName,

    // 회원가입 완료 시각
    Instant createdAt
) {
    // Users Entity를 회원가입 응답 DTO로 변환하는 메서드
    public static UsersSignupResponseDto from(Users user) {
        return new UsersSignupResponseDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getCreatedAt()
        );
    }
}
