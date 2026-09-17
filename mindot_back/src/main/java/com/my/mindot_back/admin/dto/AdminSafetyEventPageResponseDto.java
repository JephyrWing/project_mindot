// 관리자 안전 신호 목록과 페이지 정보를 프론트에 전달
package com.my.mindot_back.admin.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record AdminSafetyEventPageResponseDto(
        List<AdminSafetyEventListResponseDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    // 안전 신호 목록 페이지를 응답 DTO로 변환
    public static AdminSafetyEventPageResponseDto from(
            Page<AdminSafetyEventListResponseDto> result
    ) {
        return new AdminSafetyEventPageResponseDto(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }
}