// 감정 기록 목록의 페이지 조회 결과를 반환하는 DTO
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.entity.EmotionRecords;
import org.springframework.data.domain.Page;

import java.util.List;

public record EmotionRecordsPageResponseDto(
        List<EmotionRecordsListItemResponseDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static EmotionRecordsPageResponseDto from(
            Page<EmotionRecords> emotionRecordsPage
    ) {
        return new EmotionRecordsPageResponseDto(
                emotionRecordsPage.getContent()
                        .stream()
                        .map(EmotionRecordsListItemResponseDto::from)
                        .toList(),
                emotionRecordsPage.getNumber(),
                emotionRecordsPage.getSize(),
                emotionRecordsPage.getTotalElements(),
                emotionRecordsPage.getTotalPages()
        );
    }
}