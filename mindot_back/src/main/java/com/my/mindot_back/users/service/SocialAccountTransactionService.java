// 검증된 소셜 계정 정보를 기존 회원에 연결하거나 신규 회원으로 생성하는 DB 트랜잭션 Service
package com.my.mindot_back.users.service;

import com.my.mindot_back.users.entity.AccountStatus;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SocialAccountTransactionService {

    private final UsersRepository usersRepository;

    /*
     * 1. 고유 ID로 기존 연결 회원을 우선 조회
     * 2. 없으면 검증된 이메일로 기존 회원 조회 후 소셜 계정 연결
     * 3. 이메일 회원도 없으면 소셜 전용 회원 생성
     * 외부 카카오·구글 API 호출은 이 트랜잭션에 포함하지 않음
     */

    @Transactional
    public Users findOrCreateUser(
            OAuthProvider provider,
            String providerUserId,
            String email,
            String displayName
    ) {
        String normalizedProviderUserId = requireProviderUserId(
                providerUserId
        );
        String normalizedEmail = requireEmail(email);

        // 이미 ID가 연결된 회원이면 같은 회원으로 로그인
        Users linkedUser = findByProviderLink(
                provider,
                normalizedProviderUserId
        ).orElse(null);

        if (linkedUser != null) {
            validateActive(linkedUser);
            return linkedUser;
        }

        // 같은 이메일의 기존 회원이면 해당 행에 ID만 연결
        Users existingEmailUser = usersRepository
                .findByEmail(normalizedEmail)
                .orElse(null);

        if (existingEmailUser != null) {
            validateActive(existingEmailUser);
            validateLinkIsAvailable(
                    existingEmailUser,
                    provider,
                    normalizedProviderUserId
            );
            linkAccount(
                    existingEmailUser,
                    provider,
                    normalizedProviderUserId
            );
            return existingEmailUser;
        }

        // 이메일 회원이 없으면 검증된 소셜 이메일로 신규 회원 생성
        Users newUser = Users.createSocial(
                normalizedEmail,
                normalizeDisplayName(displayName, normalizedEmail)
        );
        linkAccount(
                newUser,
                provider,
                normalizedProviderUserId
        );

        return usersRepository.save(newUser);
    }

    // 플랫폼별 연결 컬럼에서 기존 회원 조회
    private java.util.Optional<Users> findByProviderLink(
            OAuthProvider provider,
            String providerUserId
    ){
        return switch (provider) {
            case KAKAO -> usersRepository
                    .findByKakaoAccountLink(providerUserId);
            case GOOGLE ->  usersRepository
                    .findByGoogleAccountLink(providerUserId);
        };
    }
    // 회원 행에 카카오 회원번호 또는 구글 sub를 저장
    private void linkAccount(
            Users user,
            OAuthProvider provider,
            String providerUserId
    ) {
        switch (provider) {
            case KAKAO -> user.linkKakaoAccount(providerUserId);
            case GOOGLE -> user.linkGoogleAccount(providerUserId);
        }
    }

    // 이미 다른 동일 플랫폼 계정이 연결된 기존 회원인지 검사
    private void validateLinkIsAvailable(
            Users user,
            OAuthProvider provider,
            String providerUserId
    ){
        String existingLink = switch (provider){
                case KAKAO -> user.getKakaoAccountLink();
                case GOOGLE -> user.getGoogleAccountLink();
        };
        if (existingLink != null
                    && !existingLink.equals(providerUserId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "이미 다른 " + providerName(provider)
                            + " 계정과 연결된 회원입니다."
            );
        }
    }

    // ACTIVE 상태가 아닌 정지·탈퇴 회원의 로그인 차단
    private void validateActive(Users user) {
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인할 수 없는 계정입니다."
            );
        }
    }

    // 외부 제공자가 전달한 고유 식별자가 비어 있는지 검사
    private String requireProviderUserId(String providerUserId) {
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "소셜 로그인 사용자 식별자를 확인할 수 없습니다."
            );
        }
        return providerUserId.trim();
    }

    // 이메일 동의 누락 시 가짜 이메일을 만들지 않고 로그인 거절
    private String requireEmail(String email) {
        if  (email == null || email.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "소셜 로그인에 이메일 제공 동의가 필요합니다."
            );
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    // 제공자 닉네임이 없거나 너무 길 때 DB display_name 제약에 맞게 보정
    private String normalizeDisplayName(
            String displayName,
            String email
    ){
        String fallback = email.substring(0, email.indexOf("@"));
        String normalized = displayName == null || displayName.isBlank()
                ? fallback
                : displayName.trim();

        return normalized.length() > 80
                ? normalized.substring(0, 80)
                : normalized;
    }

    // 오류 문구에 사용할 제공자 한글 이름 반환
    private String providerName(OAuthProvider provider) {
        return provider == OAuthProvider.KAKAO
                ? "카카오"
                : "구글";
    }
}
