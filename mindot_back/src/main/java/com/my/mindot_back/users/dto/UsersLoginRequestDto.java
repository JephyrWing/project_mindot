// 로그인 요청시 프론트가 보내는 JSON 데이터를 받는 DTO
package com.my.mindot_back.users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UsersLoginRequestDto (
    @NotBlank
    @Email
    String email,

    @NotBlank
    @Size(min = 8)
    String password
) {
}
