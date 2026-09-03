// 관리자가 안전 신호가 발생한 감정 기록을 확인할 때 사용하는 응답 DTO
package com.my.mindot_back.admin.dto;

import com.my.mindot_back.safety.entity.RiskLevel;
import com.my.mindot_back.safety.entity.SafetyActionCode;
import com.my.mindot_back.safety.entity.SafetyEvents;

import java.time.Instant;

public record AdminSafetyEventDetailResponseDto (
        Long safetyEventId,
        Long emotionRecordId,
        Long userId,
        String email,
        String displayName,
        String rawText,
        Instant occurredAt,
        RiskLevel riskLevel,
        String reasonCode,
        SafetyActionCode actionCode,
        Instant createdAt
){
    // SafetyEvents Entity를 관리자 안전 신호 상세 응답으로 변환
    public static AdminSafetyEventDetailResponseDto from(
            SafetyEvents safetyEvent
    ) {
        return new AdminSafetyEventDetailResponseDto(
                safetyEvent.getId(),
                safetyEvent.getEmotionRecords().getId(),
                safetyEvent.getEmotionRecords().getUser().getId(),
                safetyEvent.getEmotionRecords().getUser().getEmail(),
                safetyEvent.getEmotionRecords().getUser().getDisplayName(),
                safetyEvent.getEmotionRecords().getRawText(),
                safetyEvent.getEmotionRecords().getOccurredAt(),
                safetyEvent.getRiskLevel(),
                safetyEvent.getReasonCode(),
                safetyEvent.getActionCode(),
                safetyEvent.getCreatedAt()
        );
    }
}
