// 기관 검색 API의 정상·빈 결과·좌표 누락·입력 오류·외부 장애 응답을 검증

package com.my.mindot_back.centers;

import com.my.mindot_back.centers.client.KakaoLocalClient;
import com.my.mindot_back.centers.dto.CenterSearchItemResponseDto;
import com.my.mindot_back.centers.dto.CenterSearchPageResponseDto;
import com.my.mindot_back.centers.entity.CenterType;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CenterSearchApiTest
        extends PostgresContainerTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private KakaoLocalClient kakaoLocalClient;

    private String accessToken;

    @BeforeEach
    void setUp() {
        Users user =
                usersRepository.saveAndFlush(
                        Users.create(
                                "center-search@example.com",
                                "unused-password-hash",
                                "기관 검색 사용자"
                        )
                );

        accessToken =
                jwtTokenProvider.createAccessToken(
                        user.getId()
                );
    }

    @Test
    void normalAndEmptyResultsPreservePagingContract()
            throws Exception {
        CenterSearchItemResponseDto center =
                new CenterSearchItemResponseDto(
                        "center-101",
                        "마음봄 심리상담센터",
                        CenterType.COUNSELING_CENTER,
                        "의료,건강 > 상담",
                        "서울특별시 중구 마음동 10",
                        "서울특별시 중구 마음로 10",
                        "02-1234-5678",
                        126.9784,
                        37.5666,
                        "https://place.map.kakao.com/101"
                );

        when(
                kakaoLocalClient.search(
                        "서울특별시 중구 심리상담센터",
                        CenterType.COUNSELING_CENTER,
                        1,
                        2
                )
        ).thenReturn(
                new CenterSearchPageResponseDto(
                        List.of(center),
                        1,
                        2,
                        3,
                        2
                )
        );

        mockMvc.perform(
                        get("/api/centers")
                                .param(
                                        "region",
                                        " 서울특별시 "
                                )
                                .param(
                                        "district",
                                        " 중구 "
                                )
                                .param(
                                        "type",
                                        "COUNSELING_CENTER"
                                )
                                .param("page", "1")
                                .param("size", "2")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.page")
                                .value(1)
                )
                .andExpect(
                        jsonPath("$.size")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(3)
                )
                .andExpect(
                        jsonPath("$.totalPages")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.content.length()")
                                .value(1)
                )
                .andExpect(
                        jsonPath("$.content[0].centerId")
                                .value("center-101")
                )
                .andExpect(
                        jsonPath("$.content[0].name")
                                .value("마음봄 심리상담센터")
                )
                .andExpect(
                        jsonPath("$.content[0].centerType")
                                .value("COUNSELING_CENTER")
                )
                .andExpect(
                        jsonPath("$.content[0].longitude")
                                .value(126.9784)
                )
                .andExpect(
                        jsonPath("$.content[0].latitude")
                                .value(37.5666)
                );

        verify(kakaoLocalClient).search(
                "서울특별시 중구 심리상담센터",
                CenterType.COUNSELING_CENTER,
                1,
                2
        );

        when(
                kakaoLocalClient.search(
                        "제주특별자치도 정신건강복지센터",
                        CenterType.MENTAL_HEALTH_CENTER,
                        0,
                        10
                )
        ).thenReturn(
                new CenterSearchPageResponseDto(
                        List.of(),
                        0,
                        10,
                        0,
                        0
                )
        );

        mockMvc.perform(
                        get("/api/centers")
                                .param(
                                        "region",
                                        "제주특별자치도"
                                )
                                .param(
                                        "type",
                                        "MENTAL_HEALTH_CENTER"
                                )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.content")
                                .isEmpty()
                )
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(0)
                )
                .andExpect(
                        jsonPath("$.totalPages")
                                .value(0)
                );
    }

    @Test
    void invalidCoordinatesAreReturnedAsNull()
            throws Exception {
        CenterSearchItemResponseDto center =
                new CenterSearchItemResponseDto(
                        "center-invalid-coordinate",
                        "좌표 확인 상담센터",
                        CenterType.COUNSELING_CENTER,
                        "상담",
                        "부산광역시 중구",
                        "",
                        "",
                        null,
                        null,
                        "https://place.map.kakao.com/202"
                );

        when(
                kakaoLocalClient.search(
                        "부산광역시 중구 심리상담센터",
                        CenterType.COUNSELING_CENTER,
                        0,
                        10
                )
        ).thenReturn(
                new CenterSearchPageResponseDto(
                        List.of(center),
                        0,
                        10,
                        1,
                        1
                )
        );

        mockMvc.perform(
                        get("/api/centers")
                                .param(
                                        "region",
                                        "부산광역시"
                                )
                                .param(
                                        "district",
                                        "중구"
                                )
                                .param(
                                        "type",
                                        "COUNSELING_CENTER"
                                )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.content[0].longitude"
                        ).value(
                                org.hamcrest.Matchers.nullValue()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].latitude"
                        ).value(
                                org.hamcrest.Matchers.nullValue()
                        )
                );
    }

    @Test
    void invalidSearchConditionsReturnBadRequest()
            throws Exception {
        mockMvc.perform(
                        get("/api/centers")
                                .param("region", " ")
                                .param(
                                        "type",
                                        "COUNSELING_CENTER"
                                )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.status")
                                .value(400)
                );

        mockMvc.perform(
                        get("/api/centers")
                                .param("region", "서울특별시")
                                .param(
                                        "type",
                                        "COUNSELING_CENTER"
                                )
                                .param("page", "45")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        get("/api/centers")
                                .param("region", "서울특별시")
                                .param(
                                        "type",
                                        "COUNSELING_CENTER"
                                )
                                .param("size", "16")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(kakaoLocalClient);
    }

    @Test
    void providerClientErrorAndTimeoutReturnSafeBadGateway()
            throws Exception {
        when(
                kakaoLocalClient.search(
                        "외부4xx지역 심리상담센터",
                        CenterType.COUNSELING_CENTER,
                        0,
                        10
                )
        ).thenThrow(
                new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "기관 검색 서비스에 연결할 수 없습니다."
                )
        );

        mockMvc.perform(
                        get("/api/centers")
                                .param(
                                        "region",
                                        "외부4xx지역"
                                )
                                .param(
                                        "type",
                                        "COUNSELING_CENTER"
                                )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isBadGateway())
                .andExpect(
                        jsonPath("$.status")
                                .value(502)
                )
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "기관 검색 서비스에 연결할 수 없습니다."
                                )
                );

        when(
                kakaoLocalClient.search(
                        "타임아웃지역 정신건강복지센터",
                        CenterType.MENTAL_HEALTH_CENTER,
                        0,
                        10
                )
        ).thenThrow(
                new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "기관 검색 서비스에 연결할 수 없습니다."
                )
        );

        mockMvc.perform(
                        get("/api/centers")
                                .param(
                                        "region",
                                        "타임아웃지역"
                                )
                                .param(
                                        "type",
                                        "MENTAL_HEALTH_CENTER"
                                )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isBadGateway())
                .andExpect(
                        jsonPath("$.status")
                                .value(502)
                )
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "기관 검색 서비스에 연결할 수 없습니다."
                                )
                );
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}