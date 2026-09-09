// 카카오, 구글 OAuth 인가 URL과 CSRF 방지 state를 생성하는 Service
package com.my.mindot_back.users.service;

import com.my.mindot_back.common.auth.OAuthStateCookieManager;
import com.my.mindot_back.common.config.GoogleOAuthProperties;
import com.my.mindot_back.common.config.KakaoOAuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class OAuthAuthorizationService {

    private final KakaoOAuthProperties kakaoOAuthProperties;
    private final GoogleOAuthProperties googleOAuthProperties;
    private final OAuthStateCookieManager oAuthStateCookieManager;

    // 제공자별 인가 URL과 해당 요청에만 쓰일 state를 함께 생성
    public OAuthAuthorizationStart start(OAuthProvider provider) {
        String state = oAuthStateCookieManager.createState();

        String  authorizationUrl = switch (provider) {
            case KAKAO -> createKakaoAuthorizationUrl(state);
            case GOOGLE -> createGoogleAuthorizationUrl(state);
        };

        return new OAuthAuthorizationStart(authorizationUrl, state);
    }

    // 카카오 로그인 인가 코드 발급 페이지 URL 생성
    private String createKakaoAuthorizationUrl(String state) {
        if (kakaoOAuthProperties.restApiKey().isBlank()){
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "카카오 소셜 로그인 설정이 완료되지 않았습니다."
            );
        }

        return UriComponentsBuilder
                .fromUriString("https://kauth.kakao.com/oauth/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", kakaoOAuthProperties.restApiKey())
                .queryParam("redirect_uri", kakaoOAuthProperties.redirectUri())
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
    }

    // 구글 로그인 인가 코드 발급 페이지 URL 생성
    private String createGoogleAuthorizationUrl(String state) {
        if (googleOAuthProperties.clientId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "구글 소셜 로그인 설정이 완료되지 않았습니다."
            );
        }

        return UriComponentsBuilder
                .fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("response_type", "code")
                .queryParam("client_id", googleOAuthProperties.clientId())
                .queryParam("redirect_uri", googleOAuthProperties.redirectUri())
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
    }

    // Controller가 state 쿠키를 설정할 수 있도록 URL과 state를 함께 전달
    public record OAuthAuthorizationStart(
            String authorizationUrl,
            String state
    ){
    }
}
