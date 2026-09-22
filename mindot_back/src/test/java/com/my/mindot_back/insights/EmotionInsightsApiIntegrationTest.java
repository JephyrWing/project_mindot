// 감정 인사이트 API의 분류별 집계, 사용자 격리, 빈 값 및 오류 응답을 실제 DB로 검증
package com.my.mindot_back.insights;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmotionInsightsApiIntegrationTest extends PostgresContainerTestBase {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 9, 20);
    private static final String URL = "/api/insights/emotions";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UsersRepository usersRepository;
    @Autowired private EmotionRecordsRepository emotionRecordsRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Users user;
    private Users otherUser;
    private String accessToken;

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(Users.create(
                "insights-" + UUID.randomUUID() + "@example.com", "unused-password-hash", "인사이트 사용자"));
        otherUser = usersRepository.saveAndFlush(Users.create(
                "insights-other-" + UUID.randomUUID() + "@example.com", "unused-password-hash", "다른 사용자"));
        accessToken = jwtTokenProvider.createAccessToken(user.getId());
    }

    @Test
    void threeGroupingsCountOnlyOwnCompleteRecordsWithConsistentTotals() throws Exception {
        createCompleteRecord(user, "JOY", (short) 7, LocalTime.of(5, 30), "WORK", "COLLEAGUE");
        createCompleteRecord(user, "ANXIETY", (short) 6, LocalTime.of(9, 0), null, null);
        createCompleteRecord(user, "뿌듯함", (short) 9, LocalTime.of(10, 0), "WORK", null);
        createCompleteRecord(otherUser, "FEAR", (short) 8, LocalTime.of(11, 0), "WORK", "FRIEND");
        EmotionRecords partial = createCompleteRecord(
                user, "SADNESS", (short) 5, LocalTime.of(12, 0), "STUDY", "FAMILY");
        jdbcTemplate.update("UPDATE emotion_records SET completion_status = 'PARTIAL' WHERE id = ?", partial.getId());
        emotionRecordsRepository.saveAndFlush(EmotionRecords.createQuick(
                user, "확정 전 원문", InputType.TEXT, at(LocalTime.of(13, 0))));

        JsonNode time = getInsight("time");
        JsonNode situation = getInsight("situation");
        JsonNode relationship = getInsight("relationship");

        assertThat(time.path("groupBy").asText()).isEqualTo("time");
        assertThat(time.path("groups").get(0).path("groupCode").asText()).isEqualTo("DAWN");
        assertThat(time.path("groups").get(0).path("emotionCounts").path("JOY").asLong()).isEqualTo(1);
        assertThat(time.path("groups").get(1).path("groupCode").asText()).isEqualTo("MORNING");
        assertThat(time.path("groups").get(1).path("sampleCount").asLong()).isEqualTo(2);
        assertThat(time.path("groups").get(1).path("emotionCounts").path("뿌듯함").asLong()).isEqualTo(1);

        assertThat(group(situation, "WORK").path("sampleCount").asLong()).isEqualTo(2);
        assertThat(group(situation, "UNSPECIFIED").path("emotionCounts").path("ANXIETY").asLong())
                .isEqualTo(1);
        assertThat(group(relationship, "COLLEAGUE").path("sampleCount").asLong()).isEqualTo(1);
        assertThat(group(relationship, "UNSPECIFIED").path("sampleCount").asLong()).isEqualTo(2);

        for (JsonNode response : List.of(time, situation, relationship)) {
            assertThat(response.path("totalSampleCount").asLong()).isEqualTo(3);
            long groupTotal = 0;
            for (JsonNode currentGroup : response.path("groups")) {
                long emotionTotal = 0;
                for (JsonNode count : currentGroup.path("emotionCounts")) {
                    emotionTotal += count.asLong();
                }
                assertThat(emotionTotal).isEqualTo(currentGroup.path("sampleCount").asLong());
                groupTotal += currentGroup.path("sampleCount").asLong();
            }
            assertThat(groupTotal).isEqualTo(response.path("totalSampleCount").asLong());
        }
    }

    @Test
    void missingGroupValuesRemainInUnspecifiedGroup() throws Exception {
        createCompleteRecord(user, "JOY", (short) 8, LocalTime.of(9, 0), " ", " ");

        assertThat(group(getInsight("situation"), "UNSPECIFIED").path("sampleCount").asLong())
                .isEqualTo(1);
        assertThat(group(getInsight("relationship"), "UNSPECIFIED").path("sampleCount").asLong())
                .isEqualTo(1);
    }

    @Test
    void noCompleteRecordsReturnEmptyGroups() throws Exception {
        for (String groupBy : List.of("time", "situation", "relationship")) {
            JsonNode response = getInsight(groupBy);
            assertThat(response.path("groupBy").asText()).isEqualTo(groupBy);
            assertThat(response.path("totalSampleCount").asLong()).isZero();
            assertThat(response.path("groups").size()).isZero();
        }
    }

    @Test
    void invalidOrMissingGroupByAndUnauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get(URL).param("groupBy", "unknown")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "groupBy는 time, situation, relationship 중 하나여야 합니다."));
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("groupBy", "time"))
                .andExpect(status().isUnauthorized());
    }

    private EmotionRecords createCompleteRecord(Users owner, String code, short intensity,
                                                LocalTime time, String situation, String relationship) {
        EmotionRecords record = EmotionRecords.createQuick(owner, code + " 원문", InputType.TEXT, at(time));
        record.confirm(new EmotionRecordsConfirmRequestDto(
                code + " 상황", code + " 생각", code, intensity,
                List.of(), situation, relationship, Map.of()));
        return emotionRecordsRepository.saveAndFlush(record);
    }

    private java.time.Instant at(LocalTime time) {
        return RECORD_DATE.atTime(time).atZone(SEOUL).toInstant();
    }

    private JsonNode getInsight(String groupBy) throws Exception {
        String body = mockMvc.perform(get(URL).param("groupBy", groupBy)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode group(JsonNode response, String groupCode) {
        for (JsonNode current : response.path("groups")) {
            if (groupCode.equals(current.path("groupCode").asText())) {
                return current;
            }
        }
        throw new AssertionError("그룹을 찾을 수 없습니다: " + groupCode);
    }
}
