// 소셜 계정의 기존 회원 연결과 동의 완료 신규 회원 생성을 처리하는 DB 트랜잭션 Service
package com.my.mindot_back.users.service;

import com.my.mindot_back.redis.entity.SocialSignupTicket;
import com.my.mindot_back.users.entity.AccountStatus;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SocialAccountTransactionService {

    private final UsersRepository usersRepository;
    private final ConsentEventsService consentEventsService;

    /*
     * 소셜 인증 직후 실행:
     * 1. 소셜 고유 ID로 이미 연결된 회원 조회
     * 2. 없으면 같은 이메일의 기존 회원에 소셜 계정 연결
     * 3. 둘 다 없으면 DB에 저장하지 않고 신규 가입 대상 정보 반환
     */
    @Transactional
    public SocialAccountResolution resolveExistingAccount(
            OAuthProvider provider,
            String providerUserId,
            String email,
            String displayName
    ) {
        return resolve(
                provider,
                providerUserId,
                email,
                displayName
        );
    }

    /*
     * 필수 동의를 마친 신규 소셜 회원 생성:
     * 티켓 발급 이후 다른 요청에서 같은 회원이 생성됐을 가능성을 고려해
     * 기존 회원 여부를 다시 확인한 뒤 신규 회원과 동의 이력을 함께 저장
     */
    @Transactional
    public Users completeSignup(SocialSignupTicket ticket) {
        OAuthProvider provider = parseProvider(ticket.getProvider());

        SocialAccountResolution resolution = resolve(
                provider,
                ticket.getProviderUserId(),
                ticket.getEmail(),
                ticket.getDisplayName()
        );

        // 가입 티켓 발급 후 이미 같은 계정이 생성됐다면 중복 생성하지 않음
        if (resolution.hasExistingUser()) {
            return resolution.existingUser();
        }

        Users newUser = Users.createSocial(
                resolution.email(),
                resolution.displayName()
        );

        linkAccount(
                newUser,
                provider,
                resolution.providerUserId()
        );

        Users savedUser = usersRepository.save(newUser);

        // 신규 소셜 회원도 일반 회원과 동일한 필수 동의 이력 3건 저장
        consentEventsService.grantRequiredConsents(savedUser);

        return savedUser;
    }

    private SocialAccountResolution resolve(
            OAuthProvider provider,
            String providerUserId,
            String email,
            String displayName
    ) {
        String normalizedProviderUserId =
                requireProviderUserId(providerUserId);
        String normalizedEmail = requireEmail(email);
        String normalizedDisplayName =
                normalizeDisplayName(displayName, normalizedEmail);

        // 이미 같은 소셜 고유 ID가 연결된 회원이면 바로 로그인 대상
        Users linkedUser = findByProviderLink(
                provider,
                normalizedProviderUserId
        ).orElse(null);

        if (linkedUser != null) {
            validateActive(linkedUser);

            return SocialAccountResolution.existing(
                    linkedUser,
                    normalizedProviderUserId,
                    normalizedEmail,
                    normalizedDisplayName
            );
        }

        // 같은 이메일의 일반·다른 소셜 회원이면 현재 소셜 계정 연결
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

            return SocialAccountResolution.existing(
                    existingEmailUser,
                    normalizedProviderUserId,
                    normalizedEmail,
                    normalizedDisplayName
            );
        }

        // 완전한 신규 회원은 아직 users 테이블에 저장하지 않음
        return SocialAccountResolution.newSignup(
                normalizedProviderUserId,
                normalizedEmail,
                normalizedDisplayName
        );
    }

    private java.util.Optional<Users> findByProviderLink(
            OAuthProvider provider,
            String providerUserId
    ) {
        return switch (provider) {
            case KAKAO ->
                    usersRepository.findByKakaoAccountLink(providerUserId);
            case GOOGLE ->
                    usersRepository.findByGoogleAccountLink(providerUserId);
        };
    }

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

    private void validateLinkIsAvailable(
            Users user,
            OAuthProvider provider,
            String providerUserId
    ) {
        String existingLink = switch (provider) {
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

    private void validateActive(Users user) {
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인할 수 없는 계정입니다."
            );
        }
    }

    private String requireProviderUserId(String providerUserId) {
        if (providerUserId == null
                || providerUserId.isBlank()
                || providerUserId.trim().length() > 255) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "소셜 로그인 사용자 식별자를 확인할 수 없습니다."
            );
        }

        return providerUserId.trim();
    }

    private String requireEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "소셜 로그인에 이메일 제공 동의가 필요합니다."
            );
        }

        String normalized = email.trim().toLowerCase(Locale.ROOT);
        int atIndex = normalized.indexOf('@');

        if (normalized.length() > 255
                || atIndex <= 0
                || atIndex == normalized.length() - 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "소셜 로그인 이메일 형식이 올바르지 않습니다."
            );
        }

        return normalized;
    }

    private String normalizeDisplayName(
            String displayName,
            String email
    ) {
        String fallback = email.substring(0, email.indexOf('@'));
        String normalized =
                displayName == null || displayName.isBlank()
                        ? fallback
                        : displayName.trim();

        return normalized.length() > 80
                ? normalized.substring(0, 80)
                : normalized;
    }

    private OAuthProvider parseProvider(String provider) {
        try {
            return OAuthProvider.valueOf(provider);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "소셜 가입 티켓의 제공자 정보가 올바르지 않습니다.",
                    e
            );
        }
    }

    private String providerName(OAuthProvider provider) {
        return provider == OAuthProvider.KAKAO
                ? "카카오"
                : "구글";
    }

    // 기존 회원 로그인과 신규 가입 대기 상태를 함께 표현하는 내부 결과
    public record SocialAccountResolution(
            Users existingUser,
            String providerUserId,
            String email,
            String displayName
    ) {
        public boolean hasExistingUser() {
            return existingUser != null;
        }

        public static SocialAccountResolution existing(
                Users user,
                String providerUserId,
                String email,
                String displayName
        ) {
            return new SocialAccountResolution(
                    user,
                    providerUserId,
                    email,
                    displayName
            );
        }

        public static SocialAccountResolution newSignup(
                String providerUserId,
                String email,
                String displayName
        ) {
            return new SocialAccountResolution(
                    null,
                    providerUserId,
                    email,
                    displayName
            );
        }
    }
}