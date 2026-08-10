package com.my.mindot_back.common.exception;

import com.my.mindot_back.common.auth.RefreshTokenCookieManager;
import com.my.mindot_back.common.config.RefreshCookieProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void refreshAuthenticationFailureClearsCookieWithoutExposingToken() {
        RefreshTokenCookieManager cookieManager =
                new RefreshTokenCookieManager(
                        new RefreshCookieProperties(
                                "mindot_refresh",
                                "/api/auth",
                                false
                        )
                );
        GlobalExceptionHandler handler =
                new GlobalExceptionHandler(cookieManager);
        MockHttpServletResponse response = new MockHttpServletResponse();

        ErrorResponse error = handler.handleRefreshTokenFailure(
                new InvalidRefreshTokenException(),
                response
        );

        assertThat(error.getStatus()).isEqualTo(401);
        assertThat(error.getMessage()).doesNotContain("token");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("mindot_refresh=")
                .contains("Max-Age=0")
                .contains("Path=/api/auth")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .doesNotContain("Secure");
    }
}
