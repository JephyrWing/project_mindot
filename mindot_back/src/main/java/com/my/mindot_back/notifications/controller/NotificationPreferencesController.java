// 반복 패턴 알림 설정 HTTP API
package com.my.mindot_back.notifications.controller;

import com.my.mindot_back.notifications.dto.NotificationPreferencesResponseDto;
import com.my.mindot_back.notifications.dto.NotificationPreferencesUpdateRequestDto;
import com.my.mindot_back.notifications.service.NotificationPreferencesService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications/preferences")
@RequiredArgsConstructor
public class NotificationPreferencesController {

    private final NotificationPreferencesService
            notificationPreferencesService;

    // 현재 알림 설정 조회
    @GetMapping
    public NotificationPreferencesResponseDto getPreferences(
            @AuthenticationPrincipal Long userId
    ) {
        return notificationPreferencesService.getPreferences(userId);
    }

    // 알림 수신 여부와 희망 시각 변경
    @PutMapping
    public NotificationPreferencesResponseDto updatePreferences(
            @AuthenticationPrincipal Long userId,
            @Valid
            @RequestBody NotificationPreferencesUpdateRequestDto dto
    ) {
        return notificationPreferencesService.updatePreferences(
                userId,
                dto
        );
    }
}