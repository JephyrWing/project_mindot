// 동의 철회·재동의가 이력 추가 방식으로 처리되고 AI 기능 접근을 제어하는지 확인
package com.my.mindot_back.users.service;

import com.my.mindot_back.users.entity.ConsentAction;
import com.my.mindot_back.users.entity.ConsentEvents;
import com.my.mindot_back.users.entity.ConsentType;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.ConsentEventsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsentEventsServiceTest {

    private ConsentEventsRepository consentEventsRepository;
    private UsersRepository usersRepository;
    private ConsentEventsService service;
    private Users user;

    @BeforeEach
    void setUp() {
        consentEventsRepository = mock(ConsentEventsRepository.class);
        usersRepository = mock(UsersRepository.class);
        service = new ConsentEventsService(
                consentEventsRepository,
                usersRepository
        );
        user = Users.createSocial(
                "consent-test@example.com",
                "동의 테스트"
        );
    }

    @Test
    void revokeAiAnalysisAppendsRevokedEvent() {
        ConsentEvents granted = ConsentEvents.grant(
                user,
                ConsentType.AI_ANALYSIS,
                "ai-analysis-v1"
        );
        when(usersRepository.findLockedById(7L))
                .thenReturn(Optional.of(user));
        when(consentEventsRepository
                .findFirstByUser_IdAndConsentTypeOrderByOccurredAtDescIdDesc(
                        7L,
                        ConsentType.AI_ANALYSIS
                ))
                .thenReturn(Optional.of(granted));
        when(consentEventsRepository.save(any(ConsentEvents.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.changeConsent(
                7L,
                ConsentType.AI_ANALYSIS,
                false
        );

        assertThat(result.granted()).isFalse();
        assertThat(result.latestAction())
                .isEqualTo(ConsentAction.REVOKED);
        verify(consentEventsRepository).save(any(ConsentEvents.class));
    }

    @Test
    void duplicateRevokeDoesNotAppendAnotherEvent() {
        ConsentEvents revoked = ConsentEvents.revoke(
                user,
                ConsentType.AI_ANALYSIS,
                "ai-analysis-v1"
        );
        when(usersRepository.findLockedById(7L))
                .thenReturn(Optional.of(user));
        when(consentEventsRepository
                .findFirstByUser_IdAndConsentTypeOrderByOccurredAtDescIdDesc(
                        7L,
                        ConsentType.AI_ANALYSIS
                ))
                .thenReturn(Optional.of(revoked));

        var result = service.changeConsent(
                7L,
                ConsentType.AI_ANALYSIS,
                false
        );

        assertThat(result.granted()).isFalse();
        verify(consentEventsRepository, never())
                .save(any(ConsentEvents.class));
    }

    @Test
    void requiredConsentCannotBeRevokedIndividually() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeConsent(
                        7L,
                        ConsentType.PRIVACY,
                        false
                )
        );

        assertThat(exception.getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(usersRepository, never()).findLockedById(7L);
    }

    @Test
    void revokedAiAnalysisConsentBlocksAiFeature() {
        when(consentEventsRepository
                .findFirstByUser_IdAndConsentTypeOrderByOccurredAtDescIdDesc(
                        7L,
                        ConsentType.AI_ANALYSIS
                ))
                .thenReturn(Optional.of(ConsentEvents.revoke(
                        user,
                        ConsentType.AI_ANALYSIS,
                        "ai-analysis-v1"
                )));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.requireAiAnalysisConsent(7L)
        );

        assertThat(exception.getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
