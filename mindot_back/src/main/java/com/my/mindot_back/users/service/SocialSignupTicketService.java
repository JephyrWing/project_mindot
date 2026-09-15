// 신규 소셜 회원의 가입 정보를 Redis 티켓으로 발급하고 한 번만 검증하는 Service
package com.my.mindot_back.users.service;

import com.my.mindot_back.redis.entity.SocialSignupTicket;
import com.my.mindot_back.redis.repository.SocialSignupTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SocialSignupTicketService {

    // 가입 티켓은 발급 후 5분 동안만 유효
    private static final long TICKET_TTL_SECONDS = 300L;
    private static final int SECRET_BYTE_LENGTH = 32;

    private final SocialSignupTicketRepository repository;

    // 같은 가입 티켓의 동시 요청 중 하나만 통과시키는 Redis 작업 객체
    private final StringRedisTemplate redisTemplate;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    // 소셜 인증이 완료된 신규 회원 정보를 Redis에 저장하고 원문 티켓 반환
    public String issue(
            OAuthProvider provider,
            String providerUserId,
            String email,
            String displayName
    ) {
        String ticketId = UUID.randomUUID().toString();
        String rawTicket = createRawTicket(ticketId);
        Instant now = clock.instant();

        SocialSignupTicket ticket = new SocialSignupTicket(
                ticketId,
                hash(rawTicket),
                provider.name(),
                providerUserId,
                email,
                displayName,
                now,
                TICKET_TTL_SECONDS
        );

        repository.save(ticket);
        return rawTicket;
    }

    /*
     * 프론트가 제출한 원문 티켓의 형식·만료·해시를 검증
     * 검증이 끝나면 Redis에서 먼저 삭제하여 같은 티켓의 재사용을 막음
     */
    public SocialSignupTicket consume(String rawTicket) {
        String ticketId = extractTicketId(rawTicket);

        SocialSignupTicket ticket = repository.findById(ticketId)
                .orElseThrow(this::invalidTicket);

        if (!constantTimeEquals(
                hash(rawTicket),
                ticket.getTokenHash()
        )) {
            throw invalidTicket();
        }

        // 같은 소셜 계정에서 여러 가입 티켓이 발급돼도 하나의 가입만 허용
        String socialIdentityHash = hash(
                ticket.getProvider()
                        + ":"
                        + ticket.getProviderUserId()
        );

        String consumeLockKey =
                "auth:social-signup-consumed:" + socialIdentityHash;

        Boolean firstRequest = redisTemplate.opsForValue().setIfAbsent(
                consumeLockKey,
                "1",
                Duration.ofSeconds(TICKET_TTL_SECONDS)
        );

        if (!Boolean.TRUE.equals(firstRequest)) {
            throw invalidTicket();
        }

        repository.delete(ticket);
        return ticket;
    }

    // 브라우저에 전달할 ticketId.secret 형식의 원문 티켓 생성
    private String createRawTicket(String ticketId) {
        byte[] randomBytes = new byte[SECRET_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);

        String secret = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        return ticketId + "." + secret;
    }

    // 원문 티켓에서 Redis 조회에 사용할 UUID 식별자 추출
    private String extractTicketId(String rawTicket) {
        if (rawTicket == null || rawTicket.isBlank()) {
            throw invalidTicket();
        }

        int delimiterIndex = rawTicket.indexOf('.');

        if (delimiterIndex <= 0
                || delimiterIndex != rawTicket.lastIndexOf('.')) {
            throw invalidTicket();
        }

        String ticketId = rawTicket.substring(0, delimiterIndex);

        try {
            UUID.fromString(ticketId);
        } catch (IllegalArgumentException e) {
            throw invalidTicket();
        }

        return ticketId;
    }

    // Redis에는 가입 티켓 원문 대신 SHA-256 해시값만 저장
    private String hash(String rawTicket) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hashed = digest.digest(
                    rawTicket.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256을 사용할 수 없습니다.",
                    e
            );
        }
    }

    // 해시 비교에 걸리는 시간 차이로 티켓 값을 추측하지 못하게 비교
    private boolean constantTimeEquals(
            String first,
            String second
    ) {
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private ResponseStatusException invalidTicket() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "유효하지 않거나 만료된 소셜 가입 티켓입니다."
        );
    }
}