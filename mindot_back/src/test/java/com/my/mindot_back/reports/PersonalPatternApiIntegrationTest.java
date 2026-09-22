// 반복 패턴 목록과 근거 기록 및 피드백 계약을 실제 PostgreSQL과 MockMvc로 검증
package com.my.mindot_back.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PersonalPatternApiIntegrationTest extends PostgresContainerTestBase {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired private MockMvc mockMvc;
    // 이 프로젝트 테스트 컨텍스트에는 ObjectMapper 빈이 없으므로 응답 JSON 파싱용 인스턴스를 직접 사용
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UsersRepository usersRepository;
    @Autowired private EmotionRecordsRepository emotionRecordsRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Users user;
    private Users otherUser;
    private String token;
    private String otherToken;

    @BeforeEach
    void setUp() {
        user = createUser("pattern-owner");
        otherUser = createUser("pattern-other");
        token = jwtTokenProvider.createAccessToken(user.getId());
        otherToken = jwtTokenProvider.createAccessToken(otherUser.getId());
    }

    @AfterEach
    void cleanUp() {
        if (user == null || otherUser == null) return;
        Long first = user.getId();
        Long second = otherUser.getId();
        jdbcTemplate.update("DELETE FROM pattern_cases WHERE personal_pattern_id IN "
                + "(SELECT id FROM personal_patterns WHERE user_id IN (?, ?))", first, second);
        jdbcTemplate.update("DELETE FROM personal_patterns WHERE user_id IN (?, ?)", first, second);
        jdbcTemplate.update("DELETE FROM emotion_records WHERE user_id IN (?, ?)", first, second);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", first, second);
    }

    @Test
    void listDetailAndFeedbackKeepStableIdAndOnlyOwnedCompleteEvidence() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        EmotionRecords first = createRecord(user, "ANXIETY", today, LocalTime.of(9, 0), SEOUL);
        EmotionRecords second = createRecord(user, "ANXIETY", today, LocalTime.of(10, 0), SEOUL);
        EmotionRecords third = createRecord(user, "ANXIETY", today, LocalTime.of(11, 0), SEOUL);
        EmotionRecords partial = createRecord(user, "ANXIETY", today, LocalTime.of(9, 30), SEOUL);
        jdbcTemplate.update("UPDATE emotion_records SET completion_status = 'PARTIAL' WHERE id = ?",
                partial.getId());
        createRecord(otherUser, "ANXIETY", today, LocalTime.of(9, 15), SEOUL);
        createRecord(user, "ANXIETY", today.minusDays(56), LocalTime.of(9, 0), SEOUL);

        JsonNode list = list(token);
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(1);
        JsonNode item = list.get(0);
        long patternId = item.path("patternId").asLong();
        assertThat(patternId).isPositive();
        assertThat(item.path("emotionCode").asText()).isEqualTo("ANXIETY");
        assertThat(item.path("weekday").asText()).isEqualTo(today.getDayOfWeek().name());
        assertThat(item.path("timeBucket").asText()).isEqualTo("MORNING");
        assertThat(item.path("patternLevel").asText()).isEqualTo("RECENT");
        assertThat(item.path("occurrenceCount").asLong()).isEqualTo(3);
        assertThat(item.path("distinctDateCount").asLong()).isEqualTo(1);
        assertThat(item.path("observedWeekCount").asLong()).isEqualTo(1);
        assertThat(item.path("windowStart").asText()).isEqualTo(today.minusDays(55).toString());
        assertThat(item.path("windowEnd").asText()).isEqualTo(today.toString());
        assertThat(item.path("feedback").isNull()).isTrue();

        JsonNode detail = detail(token, patternId);
        JsonNode evidence = detail.path("evidenceRecords");
        assertThat(evidence.size()).isEqualTo(3);
        assertThat(evidence.get(0).path("emotionRecordId").asLong()).isEqualTo(third.getId());
        assertThat(evidence.get(1).path("emotionRecordId").asLong()).isEqualTo(second.getId());
        assertThat(evidence.get(2).path("emotionRecordId").asLong()).isEqualTo(first.getId());
        assertThat(evidence.get(0).path("rawText").asText()).contains("ANXIETY");
        assertThat(evidence.get(0).path("situationText").asText()).contains("ANXIETY");

        assertThat(saveFeedback(token, patternId, "HELPFUL").path("feedback").asText())
                .isEqualTo("HELPFUL");
        assertThat(detail(token, patternId).path("feedback").asText()).isEqualTo("HELPFUL");
        assertThat(list(token).get(0).path("patternId").asLong()).isEqualTo(patternId);
        assertThat(list(token).get(0).path("feedback").asText()).isEqualTo("HELPFUL");

        createRecord(user, "ANXIETY", today, LocalTime.of(8, 0), SEOUL);
        JsonNode refreshed = list(token).get(0);
        assertThat(refreshed.path("patternId").asLong()).isEqualTo(patternId);
        assertThat(refreshed.path("occurrenceCount").asLong()).isEqualTo(4);
        assertThat(refreshed.path("feedback").asText()).isEqualTo("HELPFUL");
        assertThat(detail(token, patternId).path("evidenceRecords").size()).isEqualTo(4);

        JsonNode changed = saveFeedback(token, patternId, "NOT_HELPFUL");
        assertThat(changed.path("feedback").asText()).isEqualTo("NOT_HELPFUL");
        assertThat(changed.path("feedbackAt").asText()).isNotBlank();
        assertThat(detail(token, patternId).path("feedback").asText()).isEqualTo("NOT_HELPFUL");
    }

    @Test
    void missingPatternsAndInvalidAccessFollowApiContract() throws Exception {
        assertThat(list(token).size()).isZero();
        mockMvc.perform(get("/api/patterns"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/patterns/999999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());

        LocalDate today = LocalDate.now(SEOUL);
        createRecord(user, "JOY", today, LocalTime.of(9, 0), SEOUL);
        createRecord(user, "JOY", today, LocalTime.of(10, 0), SEOUL);
        createRecord(user, "JOY", today, LocalTime.of(11, 0), SEOUL);
        long patternId = list(token).get(0).path("patternId").asLong();

        assertThat(list(otherToken).size()).isZero();
        mockMvc.perform(get("/api/patterns/" + patternId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/patterns/" + patternId + "/feedback")
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"HELPFUL\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/patterns/" + patternId + "/feedback")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/patterns/" + patternId + "/feedback")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"LATER\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/patterns/" + patternId + "/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"HELPFUL\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void currentUserTimezoneDeterminesWindowWeekdayAndTimeBucket() throws Exception {
        ZoneId losAngeles = ZoneId.of("America/Los_Angeles");
        jdbcTemplate.update("UPDATE users SET timezone = ? WHERE id = ?",
                losAngeles.getId(), user.getId());
        LocalDate today = LocalDate.now(losAngeles);
        createRecord(user, "SADNESS", today, LocalTime.of(9, 0), losAngeles);
        createRecord(user, "SADNESS", today, LocalTime.of(10, 0), losAngeles);
        createRecord(user, "SADNESS", today, LocalTime.of(11, 0), losAngeles);

        JsonNode pattern = list(token).get(0);
        assertThat(pattern.path("timeBucket").asText()).isEqualTo("MORNING");
        assertThat(pattern.path("weekday").asText()).isEqualTo(today.getDayOfWeek().name());
        assertThat(pattern.path("windowEnd").asText()).isEqualTo(today.toString());
        assertThat(detail(token, pattern.path("patternId").asLong())
                .path("evidenceRecords").size()).isEqualTo(3);
    }

    @Test
    void expiredEvidenceDisappearsFromListButStoredPatternKeepsItsIdAndFeedback()
            throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        createRecord(user, "ANGER", today, LocalTime.of(9, 0), SEOUL);
        createRecord(user, "ANGER", today, LocalTime.of(10, 0), SEOUL);
        createRecord(user, "ANGER", today, LocalTime.of(11, 0), SEOUL);
        long patternId = list(token).get(0).path("patternId").asLong();
        saveFeedback(token, patternId, "HELPFUL");

        jdbcTemplate.update("DELETE FROM emotion_records WHERE user_id = ?", user.getId());

        assertThat(list(token).size()).isZero();
        JsonNode detail = detail(token, patternId);
        assertThat(detail.path("patternId").asLong()).isEqualTo(patternId);
        assertThat(detail.path("occurrenceCount").asLong()).isZero();
        assertThat(detail.path("evidenceRecords").size()).isZero();
        assertThat(detail.path("feedback").asText()).isEqualTo("HELPFUL");
    }

    @Test
    void administratorCanUsePatternApisOnlyForOwnRecords() throws Exception {
        jdbcTemplate.update("UPDATE users SET user_role = 'ROLE_ADMIN' WHERE id = ?",
                otherUser.getId());
        LocalDate today = LocalDate.now(SEOUL);
        createRecord(user, "JOY", today, LocalTime.of(9, 0), SEOUL);
        createRecord(user, "JOY", today, LocalTime.of(10, 0), SEOUL);
        createRecord(user, "JOY", today, LocalTime.of(11, 0), SEOUL);
        long ownerPatternId = list(token).get(0).path("patternId").asLong();

        assertThat(list(otherToken).size()).isZero();
        mockMvc.perform(get("/api/patterns/" + ownerPatternId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isNotFound());
    }

    private Users createUser(String prefix) {
        return usersRepository.saveAndFlush(Users.create(
                prefix + "-" + UUID.randomUUID() + "@example.com", "unused-password-hash", prefix));
    }

    private EmotionRecords createRecord(Users owner, String emotionCode, LocalDate day,
                                        LocalTime time, ZoneId zoneId) {
        Instant occurredAt = day.atTime(time).atZone(zoneId).toInstant();
        EmotionRecords record = EmotionRecords.createQuick(
                owner, emotionCode + " 반복 패턴 원문", InputType.TEXT, occurredAt);
        record.confirm(new EmotionRecordsConfirmRequestDto(
                emotionCode + " 상황", emotionCode + " 자동 사고", emotionCode,
                (short) 7, List.of(), "OTHER", "OTHER", Map.of()));
        return emotionRecordsRepository.saveAndFlush(record);
    }

    private JsonNode list(String accessToken) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/patterns")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode detail(String accessToken, long patternId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/patterns/" + patternId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode saveFeedback(String accessToken, long patternId, String feedback)
            throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/patterns/" + patternId + "/feedback")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"" + feedback + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
