// 반복 패턴 알림 목록과 페이지 정보 응답 DTO
package com.my.mindot_back.notifications.dto;

import com.my.mindot_back.notifications.entity.PatternNotification;
import org.springframework.data.domain.Page;

import java.util.List;

public record PatternNotificationPageResponseDto(
        List<PatternNotificationResponseDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    // 알림 Entity 페이지를 응답 페이지로 변환
    public static PatternNotificationPageResponseDto from(
            Page<PatternNotification> result
    ) {
        return new PatternNotificationPageResponseDto(
                result.getContent()
                        .stream()
                        .map(PatternNotificationResponseDto::from)
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }
}