package com.my.mindot_back.redis.service;

import com.my.mindot_back.common.config.RefreshTokenProperties;
import com.my.mindot_back.common.exception.InvalidRefreshTokenException;
import com.my.mindot_back.common.exception.RefreshTokenReuseException;
import com.my.mindot_back.redis.entity.RefreshToken;
import com.my.mindot_back.redis.repository.RefreshTokenRepository;
import com.my.mindot_back.redis.repository.RefreshTokenRotationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int SECRET_BYTE_LENGTH = 32;

    private final RefreshTokenRepository repository;
    private final RefreshTokenRotationRepository rotationRepository;
    private final RefreshTokenProperties properties;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    /**
     * 로그인 성공 시 새로운 Refresh Token 세션 생성
     */
    public IssuedRefreshToken issue(Long userId) {
        Instant now = clock.instant();

        String sessionId = UUID.randomUUID().toString();
        String familyId = UUID.randomUUID().toString();
        String rawToken = createRawToken(sessionId);

        Instant absoluteExpiresAt =
                now.plus(properties.absoluteExpiration());

        long ttlSeconds = calculateTtlSeconds(
                now,
                absoluteExpiresAt
        );

        RefreshToken session = new RefreshToken(
                sessionId,
                userId,
                hash(rawToken),
                familyId,
                now,
                absoluteExpiresAt,
                ttlSeconds
        );

        repository.save(session);

        return new IssuedRefreshToken(
                userId,
                rawToken,
                now.plusSeconds(ttlSeconds),
                ttlSeconds
        );
    }

    /**
     * Access Token 재발급 시 Refresh Token Rotation
     */
    public IssuedRefreshToken rotate(String oldRawToken) {
        String sessionId = extractSessionId(oldRawToken);
        Instant now = clock.instant();

        RefreshToken session = repository.findById(sessionId)
                .orElseThrow(InvalidRefreshTokenException::new);

        String newRawToken = createRawToken(sessionId);

        RefreshTokenRotationRepository.RotationOutcome outcome =
                rotationRepository.rotate(
                        sessionId,
                        hash(oldRawToken),
                        hash(newRawToken),
                        now,
                        session.getAbsoluteExpiresAt(),
                        properties.idleExpiration().toSeconds()
                );

        if (outcome.result() ==
                RefreshTokenRotationRepository.RotationResult.REUSED) {
            throw new RefreshTokenReuseException();
        }

        if (outcome.result() !=
                RefreshTokenRotationRepository.RotationResult.ROTATED) {
            throw new InvalidRefreshTokenException();
        }

        Instant expiresAt = now.plusSeconds(outcome.expiresInSeconds());
        if (expiresAt.isAfter(session.getAbsoluteExpiresAt())) {
            expiresAt = session.getAbsoluteExpiresAt();
        }

        return new IssuedRefreshToken(
                session.getUserId(),
                newRawToken,
                expiresAt,
                outcome.expiresInSeconds()
        );
    }

    /**
     * 현재 기기 로그아웃
     */
    public void revoke(String rawToken) {
        String sessionId = extractSessionId(rawToken);

        repository.findById(sessionId)
                .filter(session -> constantTimeEquals(
                        hash(rawToken),
                        session.getTokenHash()
                ))
                .ifPresent(repository::delete);
    }

    private String createRawToken(String sessionId) {
        byte[] randomBytes = new byte[SECRET_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);

        String secret = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        return sessionId + "." + secret;
    }

    private String extractSessionId(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }

        int delimiterIndex = rawToken.indexOf('.');

        if (delimiterIndex <= 0 ||
                delimiterIndex != rawToken.lastIndexOf('.')) {
            throw new InvalidRefreshTokenException();
        }

        String sessionId = rawToken.substring(0, delimiterIndex);

        try {
            UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            throw new InvalidRefreshTokenException();
        }

        return sessionId;
    }

    private long calculateTtlSeconds(
            Instant now,
            Instant absoluteExpiresAt
    ) {
        long idleSeconds =
                properties.idleExpiration().toSeconds();

        long absoluteRemainingSeconds =
                Duration.between(now, absoluteExpiresAt).toSeconds();

        if (absoluteRemainingSeconds <= 0) {
            throw new InvalidRefreshTokenException();
        }

        return Math.min(
                idleSeconds,
                absoluteRemainingSeconds
        );
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hashed = digest.digest(
                    rawToken.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256을 사용할 수 없습니다.",
                    e
            );
        }
    }

    private boolean constantTimeEquals(
            String first,
            String second
    ) {
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII)
        );
    }
}
