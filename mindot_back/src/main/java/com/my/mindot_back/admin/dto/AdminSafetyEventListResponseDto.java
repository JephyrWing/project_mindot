// 관리자가 안전 신호 이력을 조회할 때 사용하는 응답 DTO
package com.my.mindot_back.admin.dto;

import com.my.mindot_back.safety.entity.RiskLevel;
import com.my.mindot_back.safety.entity.SafetyActionCode;
import com.my.mindot_back.safety.entity.SafetyEvents;

import java.time.Instant;

public record AdminSafetyEventListResponseDto (
        Long safetyEventId,
        Long userId,
        String email,
        String displayName,
        RiskLevel riskLevel,
        String reasonCode,
        SafetyActionCode actionCode,
        Instant noticeShownAt,
        Instant createdAt
){
    // SafetyEvents Entity를 관리자 안전신호 목록 응답으로 변환
    public static AdminSafetyEventListResponseDto from(
            SafetyEvents safetyEvent
    ){
        return new AdminSafetyEventListResponseDto(
                safetyEvent.getId(),
                safetyEvent.getEmotionRecords().getUser().getId(),
                safetyEvent.getEmotionRecords().getUser().getEmail(),
                safetyEvent.getEmotionRecords().getUser().getDisplayName(),
                safetyEvent.getRiskLevel(),
                safetyEvent.getReasonCode(),
                safetyEvent.getActionCode(),
                safetyEvent.getNoticeShownAt(),
                safetyEvent.getCreatedAt()
        );
    }
}
