// 간편 감정 기록 관련 HTTP API를 처리하는 Controller
package com.my.mindot_back.records.controller;

import com.my.mindot_back.records.dto.EmotionRecordsQuickCreateRequestDto;
import com.my.mindot_back.records.dto.EmotionRecordsQuickCreateResponseDto;
import com.my.mindot_back.records.service.EmotionRecordsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class EmotionRecordsController {

    // 간편 감정 기록 저장 로직을 호출하는 Service
    private final EmotionRecordsService emotionRecordsService;

    // 간편 감정 기록 생성 API /api/records/quick
    // JWT 필터가 검증한 로그인 사용자 ID와 JSON(프론트 요청)을 Service로 전달

    @PostMapping("/quick")
    @ResponseStatus(HttpStatus.CREATED)
    public EmotionRecordsQuickCreateResponseDto createQuickRecord(
            // JwtAuthenticationFilter가 SecurityContext에 저장한 현재 로그인 사용자 ID
            @AuthenticationPrincipal Long userId,

            // 요청 JSON을 DTO로 변환하고 @NotBlack, @NotNull 검증 수행
            @Valid @RequestBody EmotionRecordsQuickCreateRequestDto dto
    ){
        return emotionRecordsService.createQuickRecord(userId, dto);
    }
}
