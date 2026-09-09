// 프론트 OAuth 콜백이 Spring에 전달하는 인증 코드와 redirect URI DTO
package com.my.mindot_back.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OAuthLoginRequestDto(

        @NotBlank
        @Size(max = 2048)
        String code,

        @NotBlank
        @Size(max = 500)
        String redirectUri,

        @NotBlank
        @Size(max = 512)
        String state
) {
}