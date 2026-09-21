// 일반 회원가입의 입력 검증과 사용자·필수 동의의 원자적 저장을 검증

package com.my.mindot_back.users.controller;

import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.ConsentAction;
import com.my.mindot_back.users.entity.ConsentType;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.ConsentEventsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SignupApiIntegrationTest
        extends PostgresContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @MockitoSpyBean
    private ConsentEventsRepository consentEventsRepository;

    @AfterEach
    void cleanUp() {
        consentEventsRepository.deleteAll();
        usersRepository.deleteAll();
    }

    @Test
    void signupStoresUserAndThreeRequiredConsents()
            throws Exception {
        mockMvc.perform(
                        post("/api/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSignupJson(
                                        "New.User@Example.com"
                                ))
                )
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.email")
                                .value("new.user@example.com")
                )
                .andExpect(
                        jsonPath("$.displayName")
                                .value("테스트 사용자")
                )
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        Users savedUser = usersRepository
                .findByEmail("new.user@example.com")
                .orElseThrow();

        assertGrantedConsent(
                savedUser.getId(),
                ConsentType.TERMS,
                "terms-v1"
        );
        assertGrantedConsent(
                savedUser.getId(),
                ConsentType.PRIVACY,
                "privacy-v1"
        );
        assertGrantedConsent(
                savedUser.getId(),
                ConsentType.AI_ANALYSIS,
                "ai-analysis-v1"
        );

        assertThat(consentEventsRepository.count())
                .isEqualTo(3);
    }

    @Test
    void invalidSignupDoesNotStorePartialData()
            throws Exception {
        performBadRequest("""
                {
                  "email": "not-an-email",
                  "password": "password123",
                  "displayName": "사용자",
                  "termsAgreed": true,
                  "privacyAgreed": true,
                  "aiAnalysisAgreed": true
                }
                """);

        performBadRequest("""
                {
                  "email": "short-password@example.com",
                  "password": "short",
                  "displayName": "사용자",
                  "termsAgreed": true,
                  "privacyAgreed": true,
                  "aiAnalysisAgreed": true
                }
                """);

        performBadRequest("""
                {
                  "email": "missing-consent@example.com",
                  "password": "password123",
                  "displayName": "사용자",
                  "termsAgreed": false,
                  "privacyAgreed": true,
                  "aiAnalysisAgreed": true
                }
                """);

        assertThat(usersRepository.count()).isZero();
        assertThat(consentEventsRepository.count()).isZero();
    }

    @Test
    void duplicateEmailReturnsConflictWithoutAdditionalData()
            throws Exception {
        usersRepository.saveAndFlush(
                Users.create(
                        "duplicate@example.com",
                        "existing-password-hash",
                        "기존 사용자"
                )
        );

        mockMvc.perform(
                        post("/api/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSignupJson(
                                        "duplicate@example.com"
                                ))
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        assertThat(usersRepository.count()).isEqualTo(1);
        assertThat(consentEventsRepository.count()).isZero();
    }

    @Test
    void consentFailureRollsBackUserCreation()
            throws Exception {
        doThrow(
                new DataIntegrityViolationException(
                        "강제로 발생시킨 동의 저장 실패"
                )
        )
                .when(consentEventsRepository)
                .saveAll(any());

        mockMvc.perform(
                        post("/api/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSignupJson(
                                        "rollback@example.com"
                                ))
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        assertThat(
                usersRepository.existsByEmail(
                        "rollback@example.com"
                )
        ).isFalse();
        assertThat(consentEventsRepository.count()).isZero();
    }

    private void performBadRequest(String requestBody)
            throws Exception {
        mockMvc.perform(
                        post("/api/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    private void assertGrantedConsent(
            Long userId,
            ConsentType consentType,
            String expectedVersion
    ) {
        var consent = consentEventsRepository
                .findFirstByUser_IdAndConsentTypeOrderByOccurredAtDescIdDesc(
                        userId,
                        consentType
                )
                .orElseThrow();

        assertThat(consent.getAction())
                .isEqualTo(ConsentAction.GRANTED);
        assertThat(consent.getConsentVersion())
                .isEqualTo(expectedVersion);
    }

    private String validSignupJson(String email) {
        return """
                {
                  "email": "%s",
                  "password": "password123",
                  "displayName": "  테스트 사용자  ",
                  "termsAgreed": true,
                  "privacyAgreed": true,
                  "aiAnalysisAgreed": true
                }
                """.formatted(email);
    }
}