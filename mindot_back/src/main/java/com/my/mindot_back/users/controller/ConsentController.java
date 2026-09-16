// 로그인 사용자의 동의 현재 상태·변경 이력·철회·재동의 API를 처리
package com.my.mindot_back.users.controller;

import com.my.mindot_back.users.dto.ConsentHistoryPageResponseDto;
import com.my.mindot_back.users.dto.ConsentStatusResponseDto;
import com.my.mindot_back.users.entity.ConsentType;
import com.my.mindot_back.users.service.ConsentEventsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/consents")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentEventsService consentEventsService;

    // 동의 종류별 가장 최근 상태 조회
    @GetMapping
    public List<ConsentStatusResponseDto> getCurrentConsents(
            @AuthenticationPrincipal Long userId
    ) {
        return consentEventsService.getCurrentConsents(userId);
    }

    // 전체 동의·철회 이력을 최신순으로 페이징 조회
    @GetMapping("/history")
    public ConsentHistoryPageResponseDto getConsentHistory(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return consentEventsService.getConsentHistory(
                userId,
                page,
                size
        );
    }

    // 현재 버전의 선택 동의에 다시 동의
    @PostMapping("/{consentType}/grant")
    public ConsentStatusResponseDto grantConsent(
            @AuthenticationPrincipal Long userId,
            @PathVariable ConsentType consentType
    ) {
        return consentEventsService.changeConsent(
                userId,
                consentType,
                true
        );
    }

    // 선택 동의를 철회하고 새 REVOKED 이벤트 저장
    @PostMapping("/{consentType}/revoke")
    public ConsentStatusResponseDto revokeConsent(
            @AuthenticationPrincipal Long userId,
            @PathVariable ConsentType consentType
    ) {
        return consentEventsService.changeConsent(
                userId,
                consentType,
                false
        );
    }
}
