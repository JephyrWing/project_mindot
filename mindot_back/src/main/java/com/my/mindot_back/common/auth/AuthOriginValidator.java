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
