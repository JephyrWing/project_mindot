// 기관 검색 조건을 검증하고 카카오 Local 검색어를 생성하는 Service
package com.my.mindot_back.centers.service;

import com.my.mindot_back.centers.client.KakaoLocalClient;
import com.my.mindot_back.centers.dto.CenterSearchPageResponseDto;
import com.my.mindot_back.centers.entity.CenterType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CenterSearchService {

    // 카카오 키워드 장소 검색의 최대 페이지 크기
    private static final int MAX_PAGE_SIZE = 15;

    // 카카오 키워드 장소 검색은 1~45페이지를 지원
    private static final int MAX_PAGE_NUMBER = 44;

    // 비정상적으로 긴 지역 검색값이 외부 API로 전달되지 않도록 제한
    private static final int MAX_LOCATION_LENGTH = 50;

    private final KakaoLocalClient kakaoLocalClient;

    public CenterSearchPageResponseDto searchCenters(
            String region,
            String district,
            String town,
            CenterType centerType,
            int page,
            int size
    ) {
        String normalizedRegion =
                normalizeRequiredLocation(region, "시·도");
        String normalizedDistrict =
                normalizeOptionalLocation(district, "시·군·구");
        String normalizedTown =
                normalizeOptionalLocation(town, "읍·면·동");

        if (centerType == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "기관 유형을 선택해 주세요."
            );
        }

        if (page < 0 || page > MAX_PAGE_NUMBER) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "페이지 번호는 0 이상 44 이하이어야 합니다."
            );
        }

        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "페이지 크기는 1 이상 15 이하이어야 합니다."
            );
        }

        // 선택한 지역 단계까지만 포함. 예: 대구광역시 중구 심리상담센터
        String query = String.join(
                " ",
                Stream.of(
                        normalizedRegion,
                        normalizedDistrict,
                        normalizedTown,
                        centerType.getSearchKeyword()
                ).filter(location -> !location.isBlank()).toList()
        );

        return kakaoLocalClient.search(
                query,
                centerType,
                page,
                size
        );
    }

    private String normalizeRequiredLocation(
            String location,
            String fieldName
    ) {
        if (location == null || location.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + "를 선택해 주세요."
            );
        }

        String normalizedLocation = location.trim();

        if (normalizedLocation.length() > MAX_LOCATION_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + "는 50자 이하이어야 합니다."
            );
        }

        return normalizedLocation;
    }

    private String normalizeOptionalLocation(
            String location,
            String fieldName
    ) {
        if (location == null || location.isBlank()) {
            return "";
        }

        String normalizedLocation = location.trim();

        if (normalizedLocation.length() > MAX_LOCATION_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + "는 50자 이하이어야 합니다."
            );
        }

        return normalizedLocation;
    }
}
