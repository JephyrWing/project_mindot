// 반복 패턴 알림 생성·조회·읽음 처리 HTTP API
package com.my.mindot_back.notifications.controller;

import com.my.mindot_back.notifications.dto.PatternNotificationPageResponseDto;
import com.my.mindot_back.notifications.dto.PatternNotificationResponseDto;
import com.my.mindot_back.notifications.dto.UnreadNotificationCountResponseDto;
import com.my.mindot_back.notifications.service.PatternNotificationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Validated
public class PatternNotificationController {

    private final PatternNotificationService
            patternNotificationService;

    // 오늘 기준 최우선 반복 패턴 알림 생성
    @PostMapping("/patterns/generate")
    public PatternNotificationResponseDto generatePatternNotification(
            @AuthenticationPrincipal Long userId
    ) {
        return patternNotificationService.generateForUser(userId);
    }

    // 사용자 알림 최신순 조회
    @GetMapping
    public PatternNotificationPageResponseDto getNotifications(
            @AuthenticationPrincipal Long userId,

            @RequestParam(defaultValue = "0")
            @Min(
                    value = 0,
                    message = "페이지 번호는 0 이상이어야 합니다."
            )
            int page,

            @RequestParam(defaultValue = "20")
            @Min(
                    value = 1,
                    message = "페이지 크기는 1 이상이어야 합니다."
            )
            @Max(
                    value = 50,
                    message = "페이지 크기는 50 이하이어야 합니다."
            )
            int size
    ) {
        return patternNotificationService.getNotifications(
                userId,
                page,
                size
        );
    }

    // 읽지 않은 알림 수 조회
    @GetMapping("/unread-count")
    public UnreadNotificationCountResponseDto getUnreadCount(
            @AuthenticationPrincipal Long userId
    ) {
        return patternNotificationService.getUnreadCount(userId);
    }

    // 사용자 소유 알림 읽음 처리
    @PatchMapping("/{notificationId}/read")
    public PatternNotificationResponseDto markAsRead(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long notificationId
    ) {
        return patternNotificationService.markAsRead(
                userId,
                notificationId
        );
    }

    // 사용자 소유 알림 삭제
    @DeleteMapping("/{notificationId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteNotification(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long notificationId
    ) {
        patternNotificationService.deleteNotification(
                userId,
                notificationId
        );
    }
}
