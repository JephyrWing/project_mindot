// CBT 성찰 세션 시작 요청을 받는 Controller
package com.my.mindot_back.records.controller;

import com.my.mindot_back.records.dto.ReflectionSessionConfirmRequestDto;
import com.my.mindot_back.records.dto.ReflectionSessionStartResponseDto;
import com.my.mindot_back.records.dto.ReflectionSessionTurnRequestDto;
import com.my.mindot_back.records.dto.ReflectionSessionTurnResponseDto;
import com.my.mindot_back.records.service.ReflectionSessionsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reflections")
@RequiredArgsConstructor
public class ReflectionSessionsController {

    // 성찰 세션 생성, FastAPI 질문 생성을 처리하는 Service
    private final ReflectionSessionsService reflectionSessionsService;

    // 예시
    // POST /api/reflections/start/2 (2: emotion_records.id)
    @PostMapping("/start/{emotionRecordId}")
    @ResponseStatus(HttpStatus.CREATED)
    public ReflectionSessionStartResponseDto startSession(
            // JwtAuthenticationFilter가 SecurityContext에 저장한 현재 로그인 사용자 ID
            @AuthenticationPrincipal Long userId,

            // URL의 /start/{emotionRecordId} 값
            @PathVariable Long emotionRecordId
    ){
        // 사용자 확인, 감정 기록 소유권 확인, 성찰 세션 DB 생성, FastAPI 첫 질문 생성을
        // Service가 하도록 함
        return reflectionSessionsService.startSession(userId, emotionRecordId);
    }

    // 예시
    // POST /api/reflections/1/turn (1: reflection_sessions.id)
    @PostMapping("/{sessionId}/turn")
    public ReflectionSessionTurnResponseDto answerAndContinue(
            // JwtAuthenticationFilter가 SecurityContext에 저장한 현재 로그인 사용자 ID
            @AuthenticationPrincipal Long userId,

            // URL의 /{sessionId}/turn 값
            @PathVariable Long sessionId,

            // 프론트가 보낸 현재 질문의 답변 JSON
            // @Valid가 @NotBlank, @Size 검증을 실행함
            @Valid @RequestBody ReflectionSessionTurnRequestDto request
    ){
        // 답변 저장, FastAPI 다음 질문 생성, DB 저장을 Service가 처리
        return reflectionSessionsService.answerAndContinue(
                userId,
                sessionId,
                request.answer()
        );
    }

    // POST /api/reflections/1/confirm
    // 사용자가 성찰 결과와 인지왜곡 검토 결과를 최종 확정
    @PostMapping("/{sessionId}/confirm")
    // NO_CONTENT: 요청처리와 DB 저장 완료, 프론트에 돌려줄 JSON 데이터 X
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmSession(
            // JWT 인증 필터가 SecurityContext에 저장한 현재 로그인 사용자 ID
            @AuthenticationPrincipal Long userId,
            @PathVariable Long sessionId,
            // 프론트가 보낸 최종 성찰 결과와 인지왜곡 검토 결과 JSON
            // @Valid가 DTO의 필수값, 범위, 길이 검증 실행
            @Valid @RequestBody ReflectionSessionConfirmRequestDto request
    ){
        // 소유권 검증, 인지왜곡 검토 반영, 성찰 세션 최종 완료처리를 service가 함
        reflectionSessionsService.confirmSession(
                userId,
                sessionId,
                request
        );
    }
}
