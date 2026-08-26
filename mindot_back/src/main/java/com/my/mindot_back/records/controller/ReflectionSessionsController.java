// CBT 성찰 세션 시작 요청을 받는 Controller
package com.my.mindot_back.records.controller;

import com.my.mindot_back.records.dto.*;
import com.my.mindot_back.records.service.ReflectionSessionsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    // POST /api/reflections/1/cancel
    // 사용자가 진행 중인 CBT 성찰 세션을 완전히 중단
    @PostMapping("/{sessionId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelSession(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long sessionId
    ) {
        // 세션 조회, 소유권 확인, OPEN 상태 확인, CANCELLED 변경을 Service가 처리
        reflectionSessionsService.cancelSession(
                userId,
                sessionId
        );
    }

    // GET /api/reflections/open
    // 로그인 사용자의 진행 중인 OPEN CBT 성찰 세션 목록 조회
    @GetMapping("/open")
    public List<OpenReflectionSessionResponseDto> getOpenSessions(
            // JWT 인증 필터가 SecurityContext에 저장한 현재 로그인 사용자 ID
            @AuthenticationPrincipal Long userId
    ) {
        // OPEN 세션 목록 조회는 Service가 처리
        return reflectionSessionsService.getOpenSessions(userId);
    }

    // GET /api/reflections/1
    // 로그인 사용자가 자신의 CBT 성찰 세션과 질문·답변 이력을 조회
    @GetMapping("/{sessionId}")
    public ReflectionSessionDetailResponseDto getSessionDetail(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long sessionId
    ) {
        // 세션 조회와 소유권 검증은 Service가 처리
        return reflectionSessionsService.getSessionDetail(
                userId,
                sessionId
        );
    }
}
