// 사용자의 개별 동의·철회 이벤트를 이력 목록 항목으로 전달
package com.my.mindot_back.users.dto;

import com.my.mindot_back.users.entity.ConsentAction;
import com.my.mindot_back.users.entity.ConsentEvents;
import com.my.mindot_back.users.entity.ConsentType;

import java.time.Instant;

public record ConsentHistoryItemResponseDto(
        Long consentEventId,
        ConsentType consentType,
        String consentVersion,
        ConsentAction action,
        Instant occurredAt
) {
    public static ConsentHistoryItemResponseDto from(
            ConsentEvents event
    ) {
        return new ConsentHistoryItemResponseDto(
                event.getId(),
                event.getConsentType(),
                event.getConsentVersion(),
                event.getAction(),
                event.getOccurredAt()
        );
    }
}
