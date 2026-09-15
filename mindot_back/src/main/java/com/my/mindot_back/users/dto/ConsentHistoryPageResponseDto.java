// 동의 변경 이력과 페이지 정보를 프론트에 전달
package com.my.mindot_back.users.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record ConsentHistoryPageResponseDto(
        List<ConsentHistoryItemResponseDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static ConsentHistoryPageResponseDto from(
            Page<ConsentHistoryItemResponseDto> result
    ) {
        return new ConsentHistoryPageResponseDto(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }
}
