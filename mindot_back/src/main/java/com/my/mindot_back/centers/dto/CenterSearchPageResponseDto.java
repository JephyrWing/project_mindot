// 기관 검색 결과 목록과 프론트 페이지 처리에 필요한 정보를 함께 전달
package com.my.mindot_back.centers.dto;

import java.util.List;

public record CenterSearchPageResponseDto(
        List<CenterSearchItemResponseDto> content,
        int page,
        int size,
        int totalElements,
        int totalPages
) {
}