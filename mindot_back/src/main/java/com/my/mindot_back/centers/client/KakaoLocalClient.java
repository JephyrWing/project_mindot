// 카카오 Local 키워드 장소 검색 API를 호출하고 기관 목록 응답으로 변환
package com.my.mindot_back.centers.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.my.mindot_back.centers.dto.CenterSearchItemResponseDto;
import com.my.mindot_back.centers.dto.CenterSearchPageResponseDto;
import com.my.mindot_back.centers.entity.CenterType;
import com.my.mindot_back.common.config.KakaoOAuthProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Component
public class KakaoLocalClient {

    private final RestClient kakaoLocalRestClient;
    private final KakaoOAuthProperties kakaoOAuthProperties;

    public KakaoLocalClient(
            @Qualifier("kakaoLocalRestClient")
            RestClient kakaoLocalRestClient,
            KakaoOAuthProperties kakaoOAuthProperties
    ) {
        this.kakaoLocalRestClient = kakaoLocalRestClient;
        this.kakaoOAuthProperties = kakaoOAuthProperties;
    }

    // 지역과 기관 유형을 합친 검색어로 카카오 장소 검색 실행
    public CenterSearchPageResponseDto search(
            String query,
            CenterType centerType,
            int page,
            int size
    ) {
        try {
            KakaoLocalSearchResponse response =
                    kakaoLocalRestClient
                            .get()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/v2/local/search/keyword.json")
                                    .queryParam("query", query)
                                    // Mindot은 0부터, 카카오는 1부터 시작
                                    .queryParam("page", page + 1)
                                    .queryParam("size", size)
                                    .build()
                            )
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "KakaoAK "
                                            + kakaoOAuthProperties.restApiKey()
                            )
                            .retrieve()
                            .body(KakaoLocalSearchResponse.class);

            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "카카오에서 기관 정보를 받지 못했습니다."
                );
            }

            List<CenterSearchItemResponseDto> content =
                    response.documents() == null
                            ? List.of()
                            : response.documents().stream()
                              .map(document -> toResponse(
                                      document,
                                      centerType
                              ))
                              .toList();

            int totalElements =
                    response.meta() == null
                            || response.meta().pageableCount() == null
                            ? content.size()
                            : response.meta().pageableCount();

            int totalPages = totalElements == 0
                    ? 0
                    : (totalElements + size - 1) / size;

            return new CenterSearchPageResponseDto(
                    content,
                    page,
                    size,
                    totalElements,
                    totalPages
            );
        } catch (RestClientResponseException exception) {
        // 카카오가 반환한 HTTP 상태와 오류 본문을 확인
        // REST API 키는 로그에 출력하지 않음
        log.error(
                "카카오 Local 기관 검색 실패: status={}, responseBody={}",
                exception.getStatusCode(),
                exception.getResponseBodyAsString(),
                exception
        );

        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "기관 검색 서비스에 연결할 수 없습니다."
        );
    } catch (RestClientException exception) {
        // 연결 실패, 타임아웃 등 HTTP 응답 자체를 받지 못한 경우
        log.error("카카오 Local 기관 검색 연결 실패", exception);

        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "기관 검색 서비스에 연결할 수 없습니다."
        );
    }
    }

    // 카카오 장소 응답을 프론트 기관 목록 항목으로 변환
    private CenterSearchItemResponseDto toResponse(
            KakaoLocalDocument document,
            CenterType centerType
    ) {
        return new CenterSearchItemResponseDto(
                document.id(),
                document.placeName(),
                centerType,
                document.categoryName(),
                document.addressName(),
                document.roadAddressName(),
                document.phone(),
                parseCoordinate(document.x()),
                parseCoordinate(document.y()),
                document.placeUrl()
        );
    }

    // 카카오가 문자열로 반환하는 좌표를 숫자로 변환
    private Double parseCoordinate(String coordinate) {
        if (coordinate == null || coordinate.isBlank()) {
            return null;
        }

        try {
            return Double.valueOf(coordinate);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    // 카카오 장소 검색 응답의 전체 구조
    private record KakaoLocalSearchResponse(
            KakaoLocalMeta meta,
            List<KakaoLocalDocument> documents
    ) {
    }

    // 실제로 페이지 조회가 가능한 검색 결과 개수
    private record KakaoLocalMeta(
            @JsonProperty("pageable_count")
            Integer pageableCount
    ) {
    }

    // 카카오 장소 검색 결과에서 사용하는 기관 정보
    private record KakaoLocalDocument(
            String id,

            @JsonProperty("place_name")
            String placeName,

            @JsonProperty("category_name")
            String categoryName,

            @JsonProperty("address_name")
            String addressName,

            @JsonProperty("road_address_name")
            String roadAddressName,

            String phone,
            String x,
            String y,

            @JsonProperty("place_url")
            String placeUrl
    ) {
    }
}