// 관리자 전용 안전 신호 이벤트 목록·상세 조회 API를 처리하는 Controller
package com.my.mindot_back.admin.controller;

import com.my.mindot_back.admin.dto.AdminSafetyEventDetailResponseDto;
import com.my.mindot_back.admin.dto.AdminSafetyEventPageResponseDto;
import com.my.mindot_back.admin.service.AdminSafetyEventsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/safety-events")
@RequiredArgsConstructor
public class AdminSafetyEventsController {

    // 관리자 안전 신호 이벤트 목록·상세 조회 Service
    private final AdminSafetyEventsService adminSafetyEventsService;

    // 안전 신호 이벤트를 최신순으로 페이징 조회
    @GetMapping
    public AdminSafetyEventPageResponseDto getSafetyEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return adminSafetyEventsService.getSafetyEvents(page, size);
    }

    // 안전 신호에 연결된 감정 기록 원문 상세 조회
    @GetMapping("/{safetyEventId}")
    public AdminSafetyEventDetailResponseDto getSafetyEvent(
            @PathVariable Long safetyEventId
    ) {
        return adminSafetyEventsService.getSafetyEvent(safetyEventId);
    }
}
