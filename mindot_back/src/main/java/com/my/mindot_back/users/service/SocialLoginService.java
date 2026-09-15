// 카카오·구글 인증 결과를 기존 회원 로그인 또는 신규 회원 동의 절차로 연결하는 Service
package com.my.mindot_back.users.service;

import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.redis.entity.SocialSignupTicket;
import com.my.mindot_back.users.client.GoogleOAuthClient;
import com.my.mindot_back.users.client.KakaoOAuthClient;
import com.my.mindot_back.users.dto.OAuthUserInfoDto;
import com.my.mindot_back.users.dto.SocialLoginResponseDto;
import com.my.mindot_back.users.dto.UsersLoginResponseDto;
import com.my.mindot_back.users.entity.Users;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final GoogleOAuthClient googleOAuthClient;
    private final SocialAccountTransactionService
            socialAccountTransactionService;
    private final SocialSignupTicketService socialSignupTicketService;
    private final JwtTokenProvider jwtTokenProvider;

    /*
     * 제공자 인증 후:
     * 기존 회원이면 Access Token을 발급하고,
     * 신규 회원이면 DB에 저장하지 않고 가입 티켓을 발급
     */
    public SocialLoginResponseDto login(
            OAuthProvider provider,
            String authorizationCode,
            String redirectUri
    ) {
        OAuthUserInfoDto oauthUserInfo = switch (provider) {
            case KAKAO -> kakaoOAuthClient.getUserInfo(
                    authorizationCode,
                    redirectUri
            );
            case GOOGLE -> googleOAuthClient.getUserInfo(
                    authorizationCode,
                    redirectUri
            );
        };

        SocialAccountTransactionService.SocialAccountResolution resolution =
                socialAccountTransactionService.resolveExistingAccount(
                        provider,
                        oauthUserInfo.providerUserId(),
                        oauthUserInfo.email(),
                        oauthUserInfo.displayName()
                );

        if (resolution.hasExistingUser()) {
            return SocialLoginResponseDto.loginCompleted(
                    createLoginResponse(resolution.existingUser())
            );
        }

        String signupTicket = socialSignupTicketService.issue(
                provider,
                resolution.providerUserId(),
                resolution.email(),
                resolution.displayName()
        );

        return SocialLoginResponseDto.signupRequired(
                signupTicket,
                resolution.email(),
                resolution.displayName()
        );
    }

    /*
     * 신규 회원이 필수 동의를 완료하면 가입 티켓을 한 번만 사용하고
     * 회원·동의 이력을 저장한 뒤 Access Token 발급
     */
    public UsersLoginResponseDto completeSignup(String rawSignupTicket) {
        SocialSignupTicket ticket =
                socialSignupTicketService.consume(rawSignupTicket);

        Users user =
                socialAccountTransactionService.completeSignup(ticket);

        return createLoginResponse(user);
    }

    private UsersLoginResponseDto createLoginResponse(Users user) {
        return new UsersLoginResponseDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                jwtTokenProvider.createAccessToken(user.getId()),
                user.getUserRole().name()
        );
    }
}