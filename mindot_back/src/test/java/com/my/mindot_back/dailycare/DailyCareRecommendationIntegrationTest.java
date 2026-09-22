// 마음 돌봄 추천 우선순위와 피드백 저장 및 소유권을 실제 DB로 검증
package com.my.mindot_back.dailycare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.dailycare.service.DailyCareRecommendationService;
import com.my.mindot_back.records.dto.EmotionRecordsConfirmRequestDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.InputType;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DailyCareRecommendationIntegrationTest extends PostgresContainerTestBase {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String URL = "/api/daily-care/recommendation";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UsersRepository usersRepository;
    @Autowired private EmotionRecordsRepository emotionRecordsRepository;
    @Autowired private DailyCareRecommendationService recommendationService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Users user;
    private Users otherUser;
    private String token;
    private String otherToken;

    @BeforeEach
    void setUp() {
        user = createUser("daily-care-primary");
        otherUser = createUser("daily-care-other");
        token = jwtTokenProvider.createAccessToken(user.getId());
        otherToken = jwtTokenProvider.createAccessToken(otherUser.getId());
    }

    @AfterEach
    void cleanUp() {
        for (Users current : List.of(user, otherUser)) {
            jdbcTemplate.update(
                    "DELETE FROM daily_care_recommendations WHERE user_id = ?",
                    current.getId()
            );
            jdbcTemplate.update(
                    "DELETE FROM emotion_records WHERE user_id = ?",
                    current.getId()
            );
            jdbcTemplate.update(
                    "DELETE FROM users WHERE id = ?",
                    current.getId()
            );
        }
    }

    @Test
    void recentJoyOverridesOlderNegativePattern() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        createRecord(user, "ANGER", (short) 8, today.minusDays(21), LocalTime.of(9, 0));
        createRecord(user, "ANGER", (short) 8, today.minusDays(14), LocalTime.of(9, 0));
        createRecord(user, "JOY", (short) 10, today.minusDays(2), LocalTime.of(9, 0));
        createRecord(user, "JOY", (short) 10, today.minusDays(1), LocalTime.of(15, 0));
        EmotionRecords latestJoy =
                createRecord(user, "JOY", (short) 10, today, LocalTime.of(21, 0));

        JsonNode result = getRecommendation(token);

        assertThat(result.path("source").asText()).isEqualTo("recent-trend");
        assertThat(result.path("activity").asText()).isEqualTo("RECORD");
        assertThat(result.path("description").asText()).contains("기쁨 감정이 3회");
        assertThat(result.path("emotionRecordId").asLong()).isEqualTo(latestJoy.getId());
    }

    @Test
    void sparseRecentRecordsUseLatestJoy() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        createRecord(user, "ANGER", (short) 8, today.minusDays(21), LocalTime.of(9, 0));
        createRecord(user, "ANGER", (short) 8, today.minusDays(14), LocalTime.of(9, 0));
        createRecord(user, "JOY", (short) 10, today, LocalTime.of(12, 0));

        JsonNode result = getRecommendation(token);

        assertThat(result.path("source").asText()).isEqualTo("latest-record");
        assertThat(result.path("activity").asText()).isEqualTo("RECORD");
    }

    @Test
    void feedbackPersistsButOtherUserCannotChangeIt() throws Exception {
        long id = getRecommendation(token).path("recommendationId").asLong();

        assertThat(saveFeedback(token, id, "HELPFUL").path("feedback").asText())
                .isEqualTo("HELPFUL");
        assertThat(getRecommendation(token).path("feedback").asText())
                .isEqualTo("HELPFUL");

        mockMvc.perform(post(feedbackUrl(id))
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"LATER\"}"))
                .andExpect(status().isNotFound());

        assertThat(getRecommendation(otherToken).path("feedback").isNull()).isTrue();

        saveFeedback(token, id, "LATER");
        assertThat(getRecommendation(token).path("feedback").asText())
                .isEqualTo("LATER");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT feedback FROM daily_care_recommendations WHERE id = ?",
                String.class,
                id
        )).isEqualTo("LATER");
    }

    @Test
    void changedRecommendationClearsFeedbackWithoutAddingDailyRow() throws Exception {
        long id = getRecommendation(token).path("recommendationId").asLong();
        saveFeedback(token, id, "HELPFUL");

        createRecord(
                user,
                "ANXIETY",
                (short) 8,
                LocalDate.now(SEOUL),
                LocalTime.of(11, 0)
        );

        JsonNode result = getRecommendation(token);

        assertThat(result.path("recommendationId").asLong()).isEqualTo(id);
        assertThat(result.path("activity").asText()).isEqualTo("BREATHING");
        assertThat(result.path("feedback").isNull()).isTrue();
        assertThat(recommendationCount()).isEqualTo(1);
    }

    @Test
    void concurrentReadsCreateOnlyOneDailyRecommendation() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Long> first = executor.submit(() -> {
                start.await();
                return recommendationService.getToday(user.getId()).recommendationId();
            });
            Future<Long> second = executor.submit(() -> {
                start.await();
                return recommendationService.getToday(user.getId()).recommendationId();
            });

            start.countDown();

            assertThat(first.get(30, TimeUnit.SECONDS))
                    .isEqualTo(second.get(30, TimeUnit.SECONDS));
            assertThat(recommendationCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Users createUser(String prefix) {
        return usersRepository.saveAndFlush(Users.create(
                prefix + "-" + UUID.randomUUID() + "@example.com",
                "unused-password-hash",
                prefix
        ));
    }

    private EmotionRecords createRecord(
            Users owner,
            String code,
            short intensity,
            LocalDate date,
            LocalTime time
    ) {
        Instant occurredAt = date.atTime(time).atZone(SEOUL).toInstant();
        EmotionRecords record = EmotionRecords.createQuick(
                owner,
                code + " 테스트 기록",
                InputType.TEXT,
                occurredAt
        );
        record.confirm(new EmotionRecordsConfirmRequestDto(
                code + " 상황",
                code + " 생각",
                code,
                intensity,
                List.of(),
                "OTHER",
                "OTHER",
                Map.of()
        ));
        return emotionRecordsRepository.saveAndFlush(record);
    }

    private JsonNode getRecommendation(String accessToken) throws Exception {
        String body = mockMvc.perform(get(URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode saveFeedback(
            String accessToken,
            long recommendationId,
            String feedback
    ) throws Exception {
        String body = mockMvc.perform(post(feedbackUrl(recommendationId))
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"" + feedback + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private String feedbackUrl(long recommendationId) {
        return "/api/daily-care/recommendations/"
                + recommendationId
                + "/feedback";
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private int recommendationCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_care_recommendations WHERE user_id = ?",
                Integer.class,
                user.getId()
        );
    }
}