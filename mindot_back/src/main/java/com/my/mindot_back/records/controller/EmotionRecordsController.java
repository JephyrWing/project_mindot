// 간편 감정 기록 관련 HTTP API를 처리하는 Controller
package com.my.mindot_back.records.controller;

import com.my.mindot_back.records.dto.*;
import com.my.mindot_back.records.service.EmotionRecordsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    // 로그인한 사용자의 감정 기록 목록 조회 API
    @GetMapping
    public List<EmotionRecordsListItemResponseDto> getEmotionRecords(
            @AuthenticationPrincipal Long userId
    ){
        // JWT에서 확인한 로그인 사용자 ID로 목록 조회
        return emotionRecordsService.getEmotionRecords(userId);
    }

    // 로그인한 사용자의 감정 기록 상세 조회 API
    @GetMapping("/{emotionRecordId}")
    public EmotionRecordsDetailResponseDto getEmotionRecordDetail(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long emotionRecordId
    ){
        // JWT 사용자 ID와 URL의 기록 ID로 본인 기록만 상세 조회
        return emotionRecordsService.getEmotionRecordsDetail(
                userId,
                emotionRecordId
        );
    }

    // 현재 감정 기록과 유사한 완료 CBT를 기반으로 패턴 설명 생성하는 API
    @PostMapping("/{emotionRecordId}/pattern-explanation")
    public  PatternExplanationResponseDto explainPattern(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long emotionRecordId
    ) {
        return emotionRecordsService.explainPattern(
                userId,
                emotionRecordId
        );
    }

    // AI 구조화 결과를 사용자가 수정, 확정하는 API
    @PostMapping("/{emotionRecordId}/confirm")
    public EmotionRecordsDetailResponseDto confirmEmotionRecord(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long emotionRecordId,
            @Valid @RequestBody EmotionRecordsConfirmRequestDto dto
    ) {
        // JWT 사용자 ID와 확정할 감정 기록 정보를 Service에 전달
        return emotionRecordsService.confirmEmotionRecord(
                userId,
                emotionRecordId,
                dto
        );
    }

    // 감정 기록 발생 시각 수정 API
    @PatchMapping("/{emotionRecordId}")
    public EmotionRecordsDetailResponseDto updateEmotionRecord(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long emotionRecordId,
            @Valid @RequestBody EmotionRecordsUpdateRequestDto dto
    ) {
        // JWT 사용자 ID와 수정할 발생 시각을 Service에 전달
        return emotionRecordsService.updateEmotionRecord(
                userId,
                emotionRecordId,
                dto
        );
    }
    // 감정기록 + 파생 데이터 함께 삭제하는 API
    @DeleteMapping("/{emotionRecordId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEmotionRecord(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long emotionRecordId
    ) {
        emotionRecordsService.deleteEmotionRecord(
                userId,
                emotionRecordId
        );
    }
}
