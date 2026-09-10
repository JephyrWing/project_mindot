// 카카오 인가 코드를 토큰으로 교환하고 검증된 사용자 정보를 조회하는 외부 API Client
package com.my.mindot_back.users.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.my.mindot_back.common.config.KakaoOAuthProperties;
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
public class KakaoOAuthClient {

    private final RestClient oauthRestClient;
    private final KakaoOAuthProperties kakaoOAuthProperties;

    public KakaoOAuthClient(
            @Qualifier("oauthRestClient") RestClient oauthRestClient,
            KakaoOAuthProperties kakaoOAuthProperties
    ) {
        this.oauthRestClient = oauthRestClient;
        this.kakaoOAuthProperties = kakaoOAuthProperties;
    }

    // 프론트가 전달한 인가 코드로 카카오 사용자 정보를 조회
    public OAuthUserInfoDto getUserInfo(
            String authorizationCode,
            String redirectUri
    ){
        validateRedirectUri (redirectUri);

        KakaoTokenResponse tokenResponse =
                requestAccessToken(authorizationCode);
        KakaoUserResponse userResponse =
                requestUserInfo(tokenResponse.accessToken());

        return toOAuthUserInfo(userResponse);
    }

    // 설정된 redirect URI와 요청값이 다르면 다른 주소의 인가 코드를 사용하지 못하게 차단
    private void validateRedirectUri (String redirectUri){
        if (!kakaoOAuthProperties.redirectUri().equals(redirectUri)){
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "카카오 로그인 redirect URI가 일치하지 않습니다."
            );
        }
    }

    // 카카오 인가 코드를 access token으로 교환
    private KakaoTokenResponse requestAccessToken(
            String authorizationCode
    ){
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", kakaoOAuthProperties.restApiKey());
        form.add("redirect_uri", kakaoOAuthProperties.redirectUri());
        form.add("code", authorizationCode);

        if (!kakaoOAuthProperties.clientSecret().isBlank()){
            form.add("client_secret", kakaoOAuthProperties.clientSecret());
        }

        try {
            KakaoTokenResponse response = oauthRestClient
                    .post()
                    .uri("https://kauth.kakao.com/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(KakaoTokenResponse.class);

            if  (response == null || response.accessToken() == null){
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "카카오 access token을 받지 못했습니다."
                );
            }
            return response;

        }  catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "카카오 로그인 토큰 요청에 실패했습니다."
            );
        }
    }

    // 카카오 access token으로 서비스 사용자 번호와 동의한 정보를 조회
    private KakaoUserResponse requestUserInfo(String accessToken) {
        try {
            KakaoUserResponse response = oauthRestClient
                    .get()
                    .uri("https://kapi.kakao.com/v2/user/me")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(KakaoUserResponse.class);

            if (response == null || response.id() == null){
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "카카오 사용자 정보를 받지 못했습니다."
                );
            }

            return response;
        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "카카오 사용자 정보 요청에 실패했습니다."
            );
        }
    }

    // 이메일 유효성, 인증 여부를 확인한 뒤 공통 사용자 DTO로 변환
    private OAuthUserInfoDto toOAuthUserInfo(
            KakaoUserResponse userResponse
    ) {
        KakaoAccount account = userResponse.kakaoAccount();

        if (account == null
                || !Boolean.TRUE.equals(account.emailValid())
                || !Boolean.TRUE.equals(account.emailVerified())
                || account.email() == null
                || account.email().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "카카오에서 인증된 이메일 정보를 제공하지 않았습니다."
            );
        }
        String displayName = account.profile() == null
                ? null
                : account.profile().nickname();

        return new OAuthUserInfoDto(
                userResponse.id().toString(),
                account.email(),
                displayName
        );
    }

    // 카카오 토큰 API 응답 중 서비스 로그인에 필요한 값
    private record KakaoTokenResponse(
            @JsonProperty("access_token") String accessToken
    ){
    }

    // 카카오 사용자 정보 API의 최상의 응답
    private record KakaoUserResponse(
            Long id,
            @JsonProperty("kakao_account") KakaoAccount kakaoAccount
    ){
    }

    // 이메일 동의, 유효성, 인증상태와 프로필 정보
    private record KakaoAccount(
            String email,
            @JsonProperty("is_email_valid") Boolean emailValid,
            @JsonProperty("is_email_verified") Boolean emailVerified,
            KakaoProfile profile
    ){
    }

    // 카카오 프로필 중 표시 이름으로 사용할 닉네임
    private record KakaoProfile(
            String nickname
    ){
    }
}
