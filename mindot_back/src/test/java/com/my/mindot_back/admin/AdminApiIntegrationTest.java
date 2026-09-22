// 관리자 API의 인증·권한·회원 목록·안전 이벤트 공개 범위를 실제 DB로 검증

package com.my.mindot_back.admin;

import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.safety.entity.RiskLevel;
import com.my.mindot_back.safety.entity.SafetyActionCode;
import com.my.mindot_back.safety.entity.SafetyEvents;
import com.my.mindot_back.safety.repository.SafetyEventsRepository;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminApiIntegrationTest
        extends PostgresContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private SafetyEventsRepository safetyEventsRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private Users operator;
    private Users targetUser;
    private String operatorToken;
    private SafetyEvents reviewEvent;
    private SafetyEvents crisisEvent;

    @BeforeEach
    void setUp() {
        operator = usersRepository.saveAndFlush(
                Users.create(
                        "admin-operator@example.com",
                        "unused-password-hash",
                        "관리자 전환 사용자"
                )
        );

        targetUser = usersRepository.saveAndFlush(
                Users.create(
                        "admin-target@example.com",
                        "unused-password-hash",
                        "안전 이벤트 사용자"
                )
        );

        operatorToken =
                jwtTokenProvider.createAccessToken(
                        operator.getId()
                );

        EmotionRecords reviewRecord =
                emotionRecordsRepository.saveAndFlush(
                        EmotionRecords.createQuick(
                                targetUser,
                                "검토 단계 안전 기록 원문",
                                InputType.TEXT,
                                Instant.parse(
                                        "2026-09-21T03:00:00Z"
                                )
                        )
                );

        EmotionRecords crisisRecord =
                emotionRecordsRepository.saveAndFlush(
                        EmotionRecords.createQuick(
                                targetUser,
                                "위기 단계 안전 기록 원문",
                                InputType.TEXT,
                                Instant.parse(
                                        "2026-09-21T04:00:00Z"
                                )
                        )
                );

        reviewEvent = safetyEventsRepository.saveAndFlush(
                SafetyEvents.create(
                        reviewRecord,
                        null,
                        RiskLevel.REVIEW,
                        "SELF_HARM_AMBIGUOUS",
                        SafetyActionCode.SHOW_REVIEW_NOTICE
                )
        );

        crisisEvent = safetyEventsRepository.saveAndFlush(
                SafetyEvents.create(
                        crisisRecord,
                        null,
                        RiskLevel.CRISIS,
                        "IMMEDIATE_DANGER",
                        SafetyActionCode.SHOW_CRISIS_NOTICE
                )
        );
    }

    @Test
    void onlyCurrentAdminCanReadUsersAndSafetyEventDetails()
            throws Exception {
        mockMvc.perform(
                        get("/api/admin/users")
                )
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.status")
                                .value(401)
                );

        mockMvc.perform(
                        get("/api/admin/users")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(operatorToken)
                                )
                )
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.status")
                                .value(403)
                );

        jdbcTemplate.update(
                """
                update users
                   set user_role = 'ROLE_ADMIN'
                 where id = ?
                """,
                operator.getId()
        );

        // 영속성 컨텍스트의 이전 ROLE_USER 값을 제거
        entityManager.clear();

        mockMvc.perform(
                        get("/api/admin/users")
                                .param("page", "0")
                                .param("size", "10")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(operatorToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.page")
                                .value(0)
                )
                .andExpect(
                        jsonPath("$.size")
                                .value(10)
                )
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.content[*].email")
                                .value(
                                        hasItem(
                                                "admin-target@example.com"
                                        )
                                )
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].passwordHash"
                        ).doesNotExist()
                );

        mockMvc.perform(
                        get("/api/admin/safety-events")
                                .param("page", "0")
                                .param("size", "10")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(operatorToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.content.length()")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.content[0].rawText")
                                .doesNotExist()
                )
                .andExpect(
                        jsonPath("$.content[1].rawText")
                                .doesNotExist()
                );

        mockMvc.perform(
                        get(
                                "/api/admin/safety-events/{eventId}",
                                reviewEvent.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(operatorToken)
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.status")
                                .value(404)
                );

        mockMvc.perform(
                        get(
                                "/api/admin/safety-events/{eventId}",
                                crisisEvent.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(operatorToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.safetyEventId")
                                .value(crisisEvent.getId())
                )
                .andExpect(
                        jsonPath("$.userId")
                                .value(targetUser.getId())
                )
                .andExpect(
                        jsonPath("$.riskLevel")
                                .value("CRISIS")
                )
                .andExpect(
                        jsonPath("$.reasonCode")
                                .value("IMMEDIATE_DANGER")
                )
                .andExpect(
                        jsonPath("$.rawText")
                                .value("위기 단계 안전 기록 원문")
                );
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}