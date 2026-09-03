// 프론트의 안전 안내 표시 확인을 처리하는 Controller
package com.my.mindot_back.safety.controller;

import com.my.mindot_back.safety.service.SafetyEventsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/safety-events")
@RequiredArgsConstructor
public class SafetyEventsController {

    // 안전 안내 이력 저장 로직 호출
    private final SafetyEventsService safetyEventsService;

    // 프론트가 안전 안내를 화면에 표시한 뒤 최초 표시 시각 저장
    @PostMapping("/{safetyEventId}/notice-shown")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markSafetyNoticeShown(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long safetyEventId
    ) {
        safetyEventsService.markSafetyNoticeShown(
                userId,
                safetyEventId
        );
    }
}
