// 구글 인가 코드를 토큰으로 교환하고 검증된 사용자 정보를 조회하는 외부 API Client
package com.my.mindot_back.users.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.my.mindot_back.common.config.GoogleOAuthProperties;
import com.my.mindot_back.users.dto.OAuthUserInfoDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Qualifier;

@Component
public class GoogleOAuthClient {

    private final RestClient oauthRestClient;
    private final GoogleOAuthProperties googleOAuthProperties;

    public GoogleOAuthClient(
            @Qualifier("oauthRestClient") RestClient oauthRestClient,
            GoogleOAuthProperties googleOAuthProperties
    ) {
        this.oauthRestClient = oauthRestClient;
        this.googleOAuthProperties = googleOAuthProperties;
    }

    // 프론트가 전달한 인가 코드로 구글 사용자 정보를 조회
    public OAuthUserInfoDto getUserInfo(
            String authorizationCode,
            String redirectUri
    ) {
        validateRedirectUri(redirectUri);

        GoogleTokenResponse tokenResponse =
                requestAccessToken(authorizationCode);
        GoogleUserInfoResponse userInfoResponse =
                requestUserInfo(tokenResponse.accessToken());

        return toOAuthUserInfo(userInfoResponse);
    }

    // 설정된 redirect URI와 요청값이 다르면 다른 주소의 인가 코드를 사용하지 못하게 차단
    private void validateRedirectUri(String redirectUri) {
        if (!googleOAuthProperties.redirectUri().equals(redirectUri)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "구글 로그인 redirect URI가 일치하지 않습니다."
            );
        }
    }

    // 구글 인가 코드를 access token으로 교환
    private GoogleTokenResponse requestAccessToken(
            String authorizationCode
    ) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", googleOAuthProperties.clientId());
        form.add("client_secret", googleOAuthProperties.clientSecret());
        form.add("redirect_uri", googleOAuthProperties.redirectUri());
        form.add("code", authorizationCode);

        try {
            GoogleTokenResponse response = oauthRestClient
                    .post()
                    .uri("https://oauth2.googleapis.com/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GoogleTokenResponse.class);

            if (response == null || response.accessToken() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "구글 access token을 받지 못했습니다."
                );
            }

            return response;
        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "구글 로그인 토큰 요청에 실패했습니다."
            );
        }
    }

    // 구글 access token으로 사용자 고유 ID와 동의한 정보를 조회
    private GoogleUserInfoResponse requestUserInfo(String accessToken) {
        try {
            GoogleUserInfoResponse response = oauthRestClient
                    .get()
                    .uri("https://openidconnect.googleapis.com/v1/userinfo")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(GoogleUserInfoResponse.class);

            if (response == null || response.sub() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "구글 사용자 정보를 받지 못했습니다."
                );
            }

            return response;
        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "구글 사용자 정보 요청에 실패했습니다."
            );
        }
    }

    // 이메일 인증 여부를 확인한 뒤 공통 사용자 DTO로 변환
    private OAuthUserInfoDto toOAuthUserInfo(
            GoogleUserInfoResponse userInfoResponse
    ) {
        if (!Boolean.TRUE.equals(userInfoResponse.emailVerified())
                || userInfoResponse.email() == null
                || userInfoResponse.email().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "구글에서 인증된 이메일 정보를 제공하지 않았습니다."
            );
        }

        return new OAuthUserInfoDto(
                userInfoResponse.sub(),
                userInfoResponse.email(),
                userInfoResponse.name()
        );
    }

    // 구글 토큰 API 응답 중 서비스 로그인에 필요한 값
    private record GoogleTokenResponse(
            @JsonProperty("access_token") String accessToken
    ) {
    }

    // 구글 userinfo API의 응답
    private record GoogleUserInfoResponse(
            String sub,
            String email,
            @JsonProperty("email_verified") Boolean emailVerified,
            String name
    ) {
    }
}