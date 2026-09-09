// 카카오, 구글 사용자 정보를 회원 연결과 JWT 발급으로 이어주는 Service
package com.my.mindot_back.users.service;

import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.users.client.GoogleOAuthClient;
import com.my.mindot_back.users.client.KakaoOAuthClient;
import com.my.mindot_back.users.dto.OAuthUserInfoDto;
import com.my.mindot_back.users.dto.UsersLoginResponseDto;
import com.my.mindot_back.users.entity.Users;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final GoogleOAuthClient googleOAuthClient;
    private final SocialAccountTransactionService socialAccountTransactionService;
    private final JwtTokenProvider jwtTokenProvider;

    // 제공자별 사용자 정보 조회 후 회원 연결과 Access Token 발급
    public UsersLoginResponseDto login(
            OAuthProvider provider,
            String authorizationCode,
            String redirectUri
    ) {
        OAuthUserInfoDto oauthUserInfo = switch (provider){
            case KAKAO -> kakaoOAuthClient.getUserInfo(
                    authorizationCode,
                    redirectUri
            );
            case GOOGLE -> googleOAuthClient.getUserInfo(
                    authorizationCode,
                    redirectUri
            );
        };

        Users user = socialAccountTransactionService.findOrCreateUser(
                provider,
                oauthUserInfo.providerUserId(),
                oauthUserInfo.email(),
                oauthUserInfo.displayName()
        );

        return new UsersLoginResponseDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                jwtTokenProvider.createAccessToken(user.getId()),
                user.getUserRole().name()
        );
    }
}