package com.my.mindot_back.users.controller;

import com.my.mindot_back.common.auth.AuthOriginValidator;
import com.my.mindot_back.common.auth.RefreshTokenCookieManager;
import com.my.mindot_back.common.config.AuthWebProperties;
import com.my.mindot_back.common.config.JwtProperties;
import com.my.mindot_back.common.config.RefreshCookieProperties;
import com.my.mindot_back.common.exception.InvalidRefreshTokenException;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.redis.service.IssuedRefreshToken;
import com.my.mindot_back.redis.service.RefreshTokenService;
import com.my.mindot_back.users.dto.TokenRefreshResponseDto;
import com.my.mindot_back.users.dto.UsersLoginRequestDto;
import com.my.mindot_back.users.dto.UsersLoginResponseDto;
import com.my.mindot_back.users.service.UsersService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsersControllerAuthTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private static final String COOKIE_NAME = "mindot_refresh";

    @Mock
    private UsersService usersService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private UsersController controller;

    @BeforeEach
    void setUp() {
        RefreshTokenCookieManager cookieManager =
                new RefreshTokenCookieManager(
                        new RefreshCookieProperties(
                                COOKIE_NAME,
                                "/api/auth",
                                true
                        )
                );
        AuthOriginValidator originValidator =
                new AuthOriginValidator(
                        new AuthWebProperties(List.of(ALLOWED_ORIGIN))
                );

        controller = new UsersController(
                usersService,
                refreshTokenService,
                cookieManager,
                originValidator,
                jwtTokenProvider,
                new JwtProperties("unused", Duration.ofMinutes(15))
        );
    }

    @Test
    void loginReturnsAccessTokenAndHttpOnlyRefreshCookie() {
        UsersLoginRequestDto requestDto =
                new UsersLoginRequestDto("user@example.com", "password123");
        UsersLoginResponseDto loginResponse =
                new UsersLoginResponseDto(
                        1L,
                        "user@example.com",
                        "user",
                        "access-token"
                );
        IssuedRefreshToken refreshToken = issuedToken(1L, "refresh-token");
        when(usersService.login(requestDto)).thenReturn(loginResponse);
        when(refreshTokenService.issue(1L)).thenReturn(refreshToken);
        String oldToken = UUID.randomUUID() + ".old";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE_NAME, oldToken));

        ResponseEntity<UsersLoginResponseDto> response = controller.login(
                requestDto,
                request
        );

        assertThat(response.getBody()).isEqualTo(loginResponse);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertRefreshCookie(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        verify(refreshTokenService).revoke(oldToken);
    }

    @Test
    void refreshRotatesCookieAndReturnsNewAccessToken() {
        String oldToken = UUID.randomUUID() + ".old";
        MockHttpServletRequest request = allowedRequest();
        request.setCookies(new Cookie(COOKIE_NAME, oldToken));
        when(refreshTokenService.rotate(oldToken))
                .thenReturn(issuedToken(1L, "new-refresh-token"));
        when(jwtTokenProvider.createAccessToken(1L)).thenReturn("new-access-token");

        ResponseEntity<TokenRefreshResponseDto> response =
                controller.refresh(request);

        assertThat(response.getBody()).isEqualTo(
                new TokenRefreshResponseDto(
                        "new-access-token",
                        "Bearer",
                        900
                )
        );
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertRefreshCookie(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
    }

    @Test
    void logoutWithoutCookieIsIdempotentAndDeletesBrowserCookie() {
        ResponseEntity<Void> response = controller.logout(allowedRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains(COOKIE_NAME + "=")
                .contains("Max-Age=0")
                .contains("Path=/api/auth")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax");
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void logoutIgnoresMalformedRefreshTokenAndDeletesCookie() {
        String malformedToken = "malformed";
        MockHttpServletRequest request = allowedRequest();
        request.setCookies(new Cookie(COOKIE_NAME, malformedToken));
        doThrow(new InvalidRefreshTokenException())
                .when(refreshTokenService).revoke(malformedToken);

        ResponseEntity<Void> response = controller.logout(request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("Max-Age=0");
    }

    @Test
    void logoutPropagatesRedisFailureInsteadOfReportingSuccess() {
        String refreshToken = UUID.randomUUID() + ".current";
        MockHttpServletRequest request = allowedRequest();
        request.setCookies(new Cookie(COOKIE_NAME, refreshToken));
        RedisConnectionFailureException redisFailure =
                new RedisConnectionFailureException("Redis unavailable");
        doThrow(redisFailure)
                .when(refreshTokenService).revoke(refreshToken);

        assertThatThrownBy(() -> controller.logout(request))
                .isSameAs(redisFailure);
    }

    @Test
    void refreshAndLogoutRejectUnlistedOrigin() {
        MockHttpServletRequest refreshRequest = new MockHttpServletRequest();
        refreshRequest.addHeader(HttpHeaders.ORIGIN, "https://evil.example");
        refreshRequest.setCookies(new Cookie(COOKIE_NAME, "ignored"));

        assertThatThrownBy(() -> controller.refresh(refreshRequest))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode.value")
                .isEqualTo(403);

        MockHttpServletRequest logoutRequest = new MockHttpServletRequest();
        logoutRequest.addHeader(HttpHeaders.ORIGIN, "https://evil.example");
        assertThatThrownBy(() -> controller.logout(logoutRequest))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode.value")
                .isEqualTo(403);

        verifyNoInteractions(refreshTokenService);
    }

    private MockHttpServletRequest allowedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
        return request;
    }

    private IssuedRefreshToken issuedToken(Long userId, String value) {
        return new IssuedRefreshToken(
                userId,
                value,
                Instant.now().plusSeconds(600),
                600
        );
    }

    private void assertRefreshCookie(String setCookie) {
        assertThat(setCookie)
                .contains(COOKIE_NAME + "=")
                .contains("Max-Age=600")
                .contains("Path=/api/auth")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax");
    }
}
