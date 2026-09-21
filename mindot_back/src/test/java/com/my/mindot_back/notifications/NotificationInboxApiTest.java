// 알림함의 목록·미확인 개수·읽음·소유권·삭제·재생성 정책을 실제 DB로 검증

package com.my.mindot_back.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.notifications.entity.NotificationPreferences;
import com.my.mindot_back.notifications.entity.PatternNotification;
import com.my.mindot_back.notifications.repository.NotificationPreferencesRepository;
import com.my.mindot_back.notifications.repository.PatternNotificationRepository;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.reports.dto.RepeatedEmotionPatternDto;
import com.my.mindot_back.reports.entity.PatternLevel;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationInboxApiTest
        extends PostgresContainerTestBase {

    private static final ZoneId SEOUL =
            ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private EmotionRecordsRepository emotionRecordsRepository;

    @Autowired
    private NotificationPreferencesRepository
            notificationPreferencesRepository;

    @Autowired
    private PatternNotificationRepository
            patternNotificationRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    private Users user;
    private Users otherUser;
    private String accessToken;
    private String otherAccessToken;

    @BeforeEach
    void setUp() {
        user = createUser(
                "notification-inbox"
        );

        otherUser = createUser(
                "notification-other"
        );

        accessToken =
                jwtTokenProvider.createAccessToken(
                        user.getId()
                );

        otherAccessToken =
                jwtTokenProvider.createAccessToken(
                        otherUser.getId()
                );

        enablePatternAlert(user);
        enablePatternAlert(otherUser);

        createRecentPattern(
                user,
                "ANXIETY"
        );

        createRecentPattern(
                otherUser,
                "SADNESS"
        );
    }

    @Test
    void inboxEnforcesOwnershipAndConsistentDeletionPolicy()
            throws Exception {
        MvcResult generatedResult =
                mockMvc.perform(
                                post(
                                        "/api/notifications/patterns/generate"
                                )
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                bearer(accessToken)
                                        )
                        )
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.emotionCode")
                                        .value("ANXIETY")
                        )
                        .andExpect(
                                jsonPath("$.read")
                                        .value(false)
                        )
                        .andReturn();

        Long generatedId =
                responseId(generatedResult);

        PatternNotification newerNotification =
                createAdditionalNotification(user);

        Instant orderingTime =
                Instant.now();

        jdbcTemplate.update(
                """
                update pattern_notifications
                   set created_at = ?
                 where id = ?
                """,
                Timestamp.from(
                        orderingTime.minusSeconds(60)
                ),
                generatedId
        );

        jdbcTemplate.update(
                """
                update pattern_notifications
                   set created_at = ?
                 where id = ?
                """,
                Timestamp.from(orderingTime),
                newerNotification.getId()
        );

        mockMvc.perform(
                        post(
                                "/api/notifications/patterns/generate"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(otherAccessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.emotionCode")
                                .value("SADNESS")
                );

        mockMvc.perform(
                        get("/api/notifications")
                                .param("page", "0")
                                .param("size", "10")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(2)
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].notificationId"
                        ).value(
                                newerNotification.getId()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.content[1].notificationId"
                        ).value(generatedId)
                );

        mockMvc.perform(
                        get(
                                "/api/notifications/unread-count"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.unreadCount")
                                .value(2)
                );

        mockMvc.perform(
                        patch(
                                "/api/notifications/{notificationId}/read",
                                generatedId
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(otherAccessToken)
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.status")
                                .value(404)
                );

        MvcResult firstReadResult =
                mockMvc.perform(
                                patch(
                                        "/api/notifications/{notificationId}/read",
                                        generatedId
                                )
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                bearer(accessToken)
                                        )
                        )
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.read")
                                        .value(true)
                        )
                        .andExpect(
                                jsonPath("$.readAt")
                                        .isNotEmpty()
                        )
                        .andReturn();

        String firstReadAt =
                responseJson(firstReadResult)
                        .get("readAt")
                        .asText();

        MvcResult repeatedReadResult =
                mockMvc.perform(
                                patch(
                                        "/api/notifications/{notificationId}/read",
                                        generatedId
                                )
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                bearer(accessToken)
                                        )
                        )
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.read")
                                        .value(true)
                        )
                        .andReturn();

        String repeatedReadAt =
                responseJson(repeatedReadResult)
                        .get("readAt")
                        .asText();

        assertThat(repeatedReadAt)
                .isEqualTo(firstReadAt);

        mockMvc.perform(
                        get(
                                "/api/notifications/unread-count"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.unreadCount")
                                .value(1)
                );

        mockMvc.perform(
                        delete(
                                "/api/notifications/{notificationId}",
                                generatedId
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(otherAccessToken)
                                )
                )
                .andExpect(status().isNotFound());

        mockMvc.perform(
                        delete(
                                "/api/notifications/{notificationId}",
                                generatedId
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        get("/api/notifications")
                                .param("page", "0")
                                .param("size", "10")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(1)
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].notificationId"
                        ).value(
                                newerNotification.getId()
                        )
                );

        mockMvc.perform(
                        post(
                                "/api/notifications/patterns/generate"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.status")
                                .value(409)
                );

        mockMvc.perform(
                        get("/api/notifications")
                                .param("page", "0")
                                .param("size", "10")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(1)
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].notificationId"
                        ).value(
                                newerNotification.getId()
                        )
                );
    }

    private Users createUser(
            String prefix
    ) {
        return usersRepository.saveAndFlush(
                Users.create(
                        prefix
                                + "-"
                                + UUID.randomUUID()
                                + "@example.com",
                        "unused-password-hash",
                        prefix + " 사용자"
                )
        );
    }

    private void enablePatternAlert(
            Users owner
    ) {
        NotificationPreferences preferences =
                NotificationPreferences
                        .createDefault(owner);

        preferences.updatePatternAlert(
                true,
                LocalTime.of(9, 0)
        );

        notificationPreferencesRepository
                .saveAndFlush(preferences);
    }

    private void createRecentPattern(
            Users owner,
            String emotionCode
    ) {
        LocalDate today =
                LocalDate.now(SEOUL);

        createRecord(
                owner,
                emotionCode,
                today.atTime(9, 0)
                        .atZone(SEOUL)
                        .toInstant()
        );

        createRecord(
                owner,
                emotionCode,
                today.atTime(9, 15)
                        .atZone(SEOUL)
                        .toInstant()
        );

        createRecord(
                owner,
                emotionCode,
                today.atTime(9, 30)
                        .atZone(SEOUL)
                        .toInstant()
        );
    }

    private void createRecord(
            Users owner,
            String emotionCode,
            Instant occurredAt
    ) {
        EmotionRecords record =
                EmotionRecords.createQuick(
                        owner,
                        emotionCode + " 알림함 기록",
                        InputType.TEXT,
                        occurredAt
                );

        record.confirm(
                new EmotionRecordsConfirmRequestDto(
                        emotionCode + " 상황",
                        emotionCode + " 자동 사고",
                        emotionCode,
                        (short) 6,
                        List.of(),
                        "OTHER",
                        "OTHER",
                        Map.of()
                )
        );

        emotionRecordsRepository.saveAndFlush(
                record
        );
    }

    private PatternNotification createAdditionalNotification(
            Users owner
    ) {
        LocalDate today =
                LocalDate.now(SEOUL);

        RepeatedEmotionPatternDto pattern =
                new RepeatedEmotionPatternDto(
                        "JOY",
                        today.getDayOfWeek().name(),
                        "EVENING",
                        3L,
                        1L,
                        1L,
                        PatternLevel.RECENT
                );

        PatternNotification notification =
                PatternNotification.create(
                        owner,
                        pattern,
                        "JOY|"
                                + today.getDayOfWeek().name()
                                + "|EVENING",
                        today.minusDays(55),
                        today,
                        "추가 반복 감정 패턴",
                        "저녁 시간대에 기쁨 감정이 반복되었습니다",
                        "기쁨을 느낀 상황을 기록해 보세요",
                        owner.getId()
                                + ":manual:"
                                + UUID.randomUUID()
                );

        return patternNotificationRepository
                .saveAndFlush(notification);
    }

    private Long responseId(
            MvcResult result
    ) throws Exception {
        return responseJson(result)
                .get("notificationId")
                .longValue();
    }

    private JsonNode responseJson(
            MvcResult result
    ) throws Exception {
        return objectMapper.readTree(
                result.getResponse()
                        .getContentAsString()
        );
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}