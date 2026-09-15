// 카카오 장소 검색 결과 중 프론트 기관 목록에 필요한 정보만 전달
package com.my.mindot_back.centers.dto;

import com.my.mindot_back.centers.entity.CenterType;

public record CenterSearchItemResponseDto(
        String centerId,
        String name,
        CenterType centerType,
        String categoryName,
        String address,
        String roadAddress,
        String phone,
        Double longitude,
        Double latitude,
        String placeUrl
) {
}