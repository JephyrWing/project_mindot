// 감정 기록 목록의 필터·정렬·페이징과 상세 조회 소유권을 실제 DB로 검증

package com.my.mindot_back.records.controller;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmotionRecordQueryApiTest
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
    private JwtTokenProvider jwtTokenProvider;

    private Users user;
    private Users otherUser;
    private String accessToken;

    private EmotionRecords recentWorkAnxiety;
    private EmotionRecords recentFamilySadness;
    private EmotionRecords oldWorkAnxiety;
    private EmotionRecords otherUsersRecord;

    @BeforeEach
    void setUp() {
        user = usersRepository.saveAndFlush(
                Users.create(
                        "record-query@example.com",
                        "unused-password-hash",
                        "기록 조회 사용자"
                )
        );

        otherUser = usersRepository.saveAndFlush(
                Users.create(
                        "other-record-query@example.com",
                        "unused-password-hash",
                        "다른 기록 사용자"
                )
        );

        LocalDate today = LocalDate.now(SEOUL);

        recentWorkAnxiety = saveCompleteRecord(
                user,
                "회의 발표가 걱정됐다",
                "ANXIETY",
                (short) 8,
                "WORK",
                today.atTime(12, 0)
                        .atZone(SEOUL)
                        .toInstant()
        );

        recentFamilySadness = saveCompleteRecord(
                user,
                "가족과 대화하지 못해 슬펐다",
                "SADNESS",
                (short) 4,
                "FAMILY",
                today.atTime(10, 0)
                        .atZone(SEOUL)
                        .toInstant()
        );

        oldWorkAnxiety = saveCompleteRecord(
                user,
                "지난 프로젝트 결과가 걱정됐다",
                "ANXIETY",
                (short) 2,
                "WORK",
                today.minusMonths(2)
                        .atTime(12, 0)
                        .atZone(SEOUL)
                        .toInstant()
        );

        otherUsersRecord = saveCompleteRecord(
                otherUser,
                "다른 사용자의 회의 걱정 기록",
                "ANXIETY",
                (short) 10,
                "WORK",
                today.atTime(13, 0)
                        .atZone(SEOUL)
                        .toInstant()
        );

        accessToken =
                jwtTokenProvider.createAccessToken(user.getId());
    }

    @Test
    void listAppliesOwnerEmotionContextKeywordAndPeriodFilters()
            throws Exception {
        mockMvc.perform(
                        get("/api/records")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .param("period", "ALL")
                                .param(
                                        "emotionCode",
                                        "ANXIETY"
                                )
                                .param(
                                        "contextCategory",
                                        "WORK"
                                )
                                .param("keyword", "걱정")
                                .param("sort", "LATEST")
                                .param("page", "0")
                                .param("size", "10")
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
                        jsonPath(
                                "$.content[0].emotionRecordId"
                        ).value(
                                recentWorkAnxiety.getId()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.content[1].emotionRecordId"
                        ).value(
                                oldWorkAnxiety.getId()
                        )
                );

        mockMvc.perform(
                        get("/api/records")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .param("period", "WEEK")
                                .param("sort", "LATEST")
                                .param("page", "0")
                                .param("size", "10")
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(2)
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].emotionRecordId"
                        ).value(
                                recentWorkAnxiety.getId()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.content[1].emotionRecordId"
                        ).value(
                                recentFamilySadness.getId()
                        )
                );
    }

    @Test
    void listSortsByIntensityAndReturnsRequestedPages()
            throws Exception {
        mockMvc.perform(
                        get("/api/records")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .param("period", "ALL")
                                .param(
                                        "sort",
                                        "INTENSITY_HIGH"
                                )
                                .param("page", "0")
                                .param("size", "1")
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.page")
                                .value(0)
                )
                .andExpect(
                        jsonPath("$.size")
                                .value(1)
                )
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(3)
                )
                .andExpect(
                        jsonPath("$.totalPages")
                                .value(3)
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].emotionRecordId"
                        ).value(
                                recentWorkAnxiety.getId()
                        )
                );

        mockMvc.perform(
                        get("/api/records")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .param("period", "ALL")
                                .param(
                                        "sort",
                                        "INTENSITY_HIGH"
                                )
                                .param("page", "1")
                                .param("size", "1")
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.content[0].emotionRecordId"
                        ).value(
                                recentFamilySadness.getId()
                        )
                );

        mockMvc.perform(
                        get("/api/records")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                                .param("period", "ALL")
                                .param(
                                        "sort",
                                        "INTENSITY_HIGH"
                                )
                                .param("page", "2")
                                .param("size", "1")
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.content[0].emotionRecordId"
                        ).value(
                                oldWorkAnxiety.getId()
                        )
                );
    }

    @Test
    void detailReturnsOwnRecordAndHidesOtherUsersRecord()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/api/records/{recordId}",
                                recentWorkAnxiety.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.emotionRecordId")
                                .value(
                                        recentWorkAnxiety.getId()
                                )
                )
                .andExpect(
                        jsonPath("$.rawText")
                                .value(
                                        "회의 발표가 걱정됐다"
                                )
                )
                .andExpect(
                        jsonPath("$.primaryEmotionCode")
                                .value("ANXIETY")
                )
                .andExpect(
                        jsonPath("$.primaryIntensity")
                                .value(8)
                )
                .andExpect(
                        jsonPath("$.contextCategory")
                                .value("WORK")
                );

        mockMvc.perform(
                        get(
                                "/api/records/{recordId}",
                                otherUsersRecord.getId()
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(
                        get(
                                "/api/records/{recordId}",
                                999999L
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    private EmotionRecords saveCompleteRecord(
            Users owner,
            String rawText,
            String emotionCode,
            Short intensity,
            String contextCategory,
            java.time.Instant occurredAt
    ) {
        EmotionRecords record =
                EmotionRecords.createQuick(
                        owner,
                        rawText,
                        InputType.TEXT,
                        occurredAt
                );

        record.confirm(
                new EmotionRecordsConfirmRequestDto(
                        rawText + " 상황",
                        rawText + " 생각",
                        emotionCode,
                        intensity,
                        List.of(),
                        contextCategory,
                        "COLLEAGUE",
                        Map.of()
                )
        );

        return emotionRecordsRepository.saveAndFlush(record);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}