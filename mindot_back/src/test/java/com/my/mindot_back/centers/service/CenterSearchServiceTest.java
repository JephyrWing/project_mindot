// 기관 검색어 조합과 지역·페이지 요청값 검증을 확인
package com.my.mindot_back.centers.service;

import com.my.mindot_back.centers.client.KakaoLocalClient;
import com.my.mindot_back.centers.dto.CenterSearchPageResponseDto;
import com.my.mindot_back.centers.entity.CenterType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CenterSearchServiceTest {

    private KakaoLocalClient kakaoLocalClient;
    private CenterSearchService service;

    @BeforeEach
    void setUp() {
        kakaoLocalClient = mock(KakaoLocalClient.class);
        service = new CenterSearchService(kakaoLocalClient);
    }

    @Test
    void searchCentersBuildsKakaoKeywordFromConditions() {
        CenterSearchPageResponseDto expected =
                new CenterSearchPageResponseDto(
                        List.of(),
                        0,
                        10,
                        0,
                        0
                );

        when(kakaoLocalClient.search(
                "경기도 가평군 가평읍 정신건강복지센터",
                CenterType.MENTAL_HEALTH_CENTER,
                0,
                10
        )).thenReturn(expected);

        CenterSearchPageResponseDto result =
                service.searchCenters(
                        " 경기도 ",
                        "가평군",
                        "가평읍",
                        CenterType.MENTAL_HEALTH_CENTER,
                        0,
                        10
                );

        assertThat(result).isSameAs(expected);
        verify(kakaoLocalClient).search(
                "경기도 가평군 가평읍 정신건강복지센터",
                CenterType.MENTAL_HEALTH_CENTER,
                0,
                10
        );
    }

    @Test
    void searchCentersRejectsBlankRegion() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.searchCenters(
                        " ",
                        "가평군",
                        "가평읍",
                        CenterType.MENTAL_HEALTH_CENTER,
                        0,
                        10
                )
        );

        assertThat(exception.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(kakaoLocalClient);
    }

    @Test
    void searchCentersRejectsPageOutsideKakaoRange() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.searchCenters(
                        "경기도",
                        "가평군",
                        "가평읍",
                        CenterType.MENTAL_HEALTH_CENTER,
                        45,
                        10
                )
        );

        assertThat(exception.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(kakaoLocalClient);
    }

    @Test
    void searchCentersRejectsSizeOutsideKakaoRange() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.searchCenters(
                        "경기도",
                        "가평군",
                        "가평읍",
                        CenterType.MENTAL_HEALTH_CENTER,
                        0,
                        16
                )
        );

        assertThat(exception.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(kakaoLocalClient);
    }
}