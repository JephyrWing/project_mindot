// 로그인 사용자의 지역·기관 유형별 관련 기관 검색 API를 처리
package com.my.mindot_back.centers.controller;

import com.my.mindot_back.centers.dto.CenterSearchPageResponseDto;
import com.my.mindot_back.centers.entity.CenterType;
import com.my.mindot_back.centers.service.CenterSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/centers")
@RequiredArgsConstructor
public class CenterSearchController {

    private final CenterSearchService centerSearchService;

    // 지역과 기관 유형을 사용해 실제 카카오 장소 목록 검색
    @GetMapping
    public CenterSearchPageResponseDto searchCenters(
            @RequestParam String region,
            @RequestParam(defaultValue = "") String district,
            @RequestParam(defaultValue = "") String town,
            @RequestParam CenterType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return centerSearchService.searchCenters(
                region,
                district,
                town,
                type,
                page,
                size
        );
    }
}
