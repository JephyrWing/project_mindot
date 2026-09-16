// 동의 종류별 현재 상태와 사용자가 변경할 수 있는지 여부를 프론트에 전달
package com.my.mindot_back.users.dto;

import com.my.mindot_back.users.entity.ConsentAction;
import com.my.mindot_back.users.entity.ConsentType;

import java.time.Instant;

public record ConsentStatusResponseDto(
        ConsentType consentType,
        String consentVersion,
        ConsentAction latestAction,
        boolean granted,
        boolean changeable,
        Instant occurredAt
) {
}
