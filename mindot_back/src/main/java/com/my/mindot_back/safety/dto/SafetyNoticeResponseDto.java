// 위험 신호 감지 후 프론트가 표시할 안전 안내와 확인 기록에 사용할 정보를 전달하는 DTO
package com.my.mindot_back.safety.dto;

import com.my.mindot_back.safety.entity.SafetyEvents;

public record SafetyNoticeResponseDto (
        Long safetyEventId,

        String riskLevel,

        String reasonCode,

        String actionCode,

        String noticeVersion
){
    // SafetyEvents Entity를 프론트 응답용 안전 안내 DTO로 변환
    public static SafetyNoticeResponseDto from(
            SafetyEvents safetyEvents
    ) {
        return new SafetyNoticeResponseDto(
                safetyEvents.getId(),
                safetyEvents.getRiskLevel().name(),
                safetyEvents.getReasonCode(),
                safetyEvents.getActionCode().name(),
                safetyEvents.getNoticeVersion()
        );
    }
}
