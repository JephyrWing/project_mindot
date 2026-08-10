// 로그인 성공 후 클라이언트에 돌려줄 데이터 DTO
package com.my.mindot_back.users.dto;

public record UsersLoginResponseDto (
    Long id,
    String email,
    String displayName,
    String accessToken
) {
}
