// CBT 성찰 세션 시작 요청을 받는 Controller
package com.my.mindot_back.records.controller;

import com.my.mindot_back.records.dto.ReflectionSessionStartResponseDto;
import com.my.mindot_back.records.service.ReflectionSessionsService;
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
        // 사용자 확인, 감정 기록 소유권 확인, 성차 세션 DB 생성, FastAPI 첫 질문 생성을
        // Service가 하도록 함
        return reflectionSessionsService.startSession(userId, emotionRecordId);
    }
}
