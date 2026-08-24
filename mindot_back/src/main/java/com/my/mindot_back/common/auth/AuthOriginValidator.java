/*
 * Refresh Token은 쿠키로 자동 전송될 수 있으므로,
 * refresh·logout 요청이 허용된 프론트엔드 Origin에서 왔는지 확인
 *
 * 허용 목록은 application.yml의 auth.web.allowed-origins에서 관리
 */
package com.my.mindot_back.common.auth;

import com.my.mindot_back.common.config.AuthWebProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class AuthOriginValidator {

    private final AuthWebProperties properties;

    public void validate(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);

        if (origin == null || !properties.allowedOrigins().contains(origin)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "허용되지 않은 요청 출처입니다."
            );
        }
    }
}
