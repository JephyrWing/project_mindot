package com.my.mindot_back.redis.service;

import com.my.mindot_back.common.config.RefreshTokenProperties;
import com.my.mindot_back.common.exception.InvalidRefreshTokenException;
import com.my.mindot_back.common.exception.RefreshTokenReuseException;
import com.my.mindot_back.redis.entity.RefreshToken;
import com.my.mindot_back.redis.repository.RefreshTokenRepository;
import com.my.mindot_back.redis.repository.RefreshTokenRotationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository repository;

    @Mock
    private RefreshTokenRotationRepository rotationRepository;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(
                repository,
                rotationRepository,
                new RefreshTokenProperties(
                        Duration.ofDays(14),
                        Duration.ofDays(30)
                )
        );
    }

    @Test
    void rotationKeepsTheOriginalAbsoluteLifetime() {
        String sessionId = UUID.randomUUID().toString();
        String rawToken = sessionId + ".old-secret";
        Instant now = Instant.now();
        Instant absoluteExpiresAt = now.plus(Duration.ofDays(5));
        RefreshToken session = new RefreshToken(
                sessionId,
                7L,
                sha256(rawToken),
                UUID.randomUUID().toString(),
                now.minusSeconds(60),
                absoluteExpiresAt,
                Duration.ofDays(5).toSeconds()
        );

        when(repository.findById(sessionId)).thenReturn(Optional.of(session));
        when(rotationRepository.rotate(
                eq(sessionId),
                eq(sha256(rawToken)),
                anyString(),
                any(Instant.class),
                eq(absoluteExpiresAt),
                anyLong()
        )).thenReturn(new RefreshTokenRotationRepository.RotationOutcome(
                RefreshTokenRotationRepository.RotationResult.ROTATED,
                Duration.ofDays(5).minusSeconds(1).toSeconds()
        ));

        IssuedRefreshToken rotated = service.rotate(rawToken);

        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(rotationRepository).rotate(
                eq(sessionId),
                eq(sha256(rawToken)),
                anyString(),
                any(Instant.class),
                eq(absoluteExpiresAt),
                ttlCaptor.capture()
        );
        assertThat(ttlCaptor.getValue())
                .isEqualTo(Duration.ofDays(14).toSeconds());
        assertThat(rotated.expiresAt()).isBeforeOrEqualTo(absoluteExpiresAt);
    }

    @Test
    void reusedTokenRevokesTheSessionAndFails() {
        String sessionId = UUID.randomUUID().toString();
        String rawToken = sessionId + ".already-used";
        Instant now = Instant.now();
        RefreshToken session = new RefreshToken(
                sessionId,
                7L,
                sha256(sessionId + ".current"),
                UUID.randomUUID().toString(),
                now,
                now.plus(Duration.ofDays(30)),
                Duration.ofDays(14).toSeconds()
        );

        when(repository.findById(sessionId)).thenReturn(Optional.of(session));
        when(rotationRepository.rotate(
                eq(sessionId),
                eq(sha256(rawToken)),
                anyString(),
                any(Instant.class),
                eq(session.getAbsoluteExpiresAt()),
                anyLong()
        )).thenReturn(new RefreshTokenRotationRepository.RotationOutcome(
                RefreshTokenRotationRepository.RotationResult.REUSED,
                0
        ));

        assertThatThrownBy(() -> service.rotate(rawToken))
                .isInstanceOf(RefreshTokenReuseException.class);
    }

    @Test
    void absolutelyExpiredSessionIsDeleted() {
        String sessionId = UUID.randomUUID().toString();
        String rawToken = sessionId + ".expired";
        Instant now = Instant.now();
        RefreshToken session = new RefreshToken(
                sessionId,
                7L,
                sha256(rawToken),
                UUID.randomUUID().toString(),
                now.minus(Duration.ofDays(31)),
                now.minusSeconds(1),
                1
        );

        when(repository.findById(sessionId)).thenReturn(Optional.of(session));
        when(rotationRepository.rotate(
                eq(sessionId),
                eq(sha256(rawToken)),
                anyString(),
                any(Instant.class),
                eq(session.getAbsoluteExpiresAt()),
                anyLong()
        )).thenReturn(new RefreshTokenRotationRepository.RotationOutcome(
                RefreshTokenRotationRepository.RotationResult.EXPIRED,
                0
        ));

        assertThatThrownBy(() -> service.rotate(rawToken))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(rotationRepository).rotate(
                eq(sessionId),
                eq(sha256(rawToken)),
                anyString(),
                any(Instant.class),
                eq(session.getAbsoluteExpiresAt()),
                anyLong()
        );
    }

    @Test
    void revokedTokenCannotBeRotatedAfterLogout() {
        String sessionId = UUID.randomUUID().toString();
        String rawToken = sessionId + ".current";
        Instant now = Instant.now();
        RefreshToken session = new RefreshToken(
                sessionId,
                7L,
                sha256(rawToken),
                UUID.randomUUID().toString(),
                now,
                now.plus(Duration.ofDays(30)),
                Duration.ofDays(14).toSeconds()
        );

        when(repository.findById(sessionId))
                .thenReturn(Optional.of(session), Optional.empty());

        service.revoke(rawToken);

        verify(repository).delete(session);
        assertThatThrownBy(() -> service.rotate(rawToken))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
