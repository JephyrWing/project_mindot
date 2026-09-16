// 로그인 사용자의 프로필 수정 요청 DTO
package com.my.mindot_back.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UsersProfileUpdateRequestDto(
        // null, 빈 문자열, 공백 문자열 입력 차단
        @NotBlank(message = "닉네임은 필수입니다.")

        // users.display_name 컬럼 길이와 동일하게 제한
        @Size(max = 80, message = "닉네임은 80자 이하여야 합니다.")
        String displayName
) {
}