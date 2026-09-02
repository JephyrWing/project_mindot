// CBT 성찰 세션 생성과 FastAPI 질문 생성을 연결하는 Service
package com.my.mindot_back.records.service;

import com.my.mindot_back.common.rag.RagUtils;
import com.my.mindot_back.records.client.FastApiCbtClient;
import com.my.mindot_back.records.dto.*;
import com.my.mindot_back.records.dto.ai.FastApiCbtResponseDto;
import com.my.mindot_back.records.entity.*;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ReflectionSessionsService {

    // 성찰 세션 저장, 조회
    private final ReflectionSessionsRepository reflectionSessionsRepository;

    // FastAPI CBT 첫 질문 생성 API 호출
    private final FastApiCbtClient fastApiCbtClient;

    // CBT 세션 저장과 AI 결과 반영을 독립 트랜잭션으로 처리
    private final ReflectionSessionStartTransactionService
            reflectionSessionStartTransactionService;

    // 트랜잭션 밖에서 OpenAI 임베딩을 생성
    private final RagUtils ragUtils;

    // CBT 답변 저장과 다음 질문 결과 반영을 독립 트랜잭션으로 처리
    private final ReflectionSessionTurnTransactionService
            reflectionSessionTurnTransactionService;

    // CBT 최종 확정과 임베딩 결과를 독립 트랜잭션으로 처리
    private final ReflectionSessionEmbeddingTransactionService
            reflectionSessionEmbeddingTransactionService;

    // CBT 세션 저장, 첫 질문 FastAPI 호출, 결과 저장을 분리해 처리
    public ReflectionSessionStartResponseDto startSession(
            Long userId,
            Long emotionRecordId
    ) {
        // 트랜잭션 A: CBT 세션과 PROCESSING AI 작업 이력 저장 후 커밋
        ReflectionSessionStartAiContext context =
                reflectionSessionStartTransactionService
                        .createSessionAndStartQuestionJob(
                                userId,
                                emotionRecordId
                        );

        FastApiCbtResponseDto fastApiResponse;
        try {
            // 트랜잭션 밖에서 첫 CBT 질문 생성 요청
            fastApiResponse = fastApiCbtClient.start(
                    context.request()
            );
        } catch (ResponseStatusException exception) {
            // 트랜잭션 B-실패: AI 작업 FAILED 상태 저장
            reflectionSessionStartTransactionService.failFirstQuestion(
                    context.aiJobId()
            );
            throw exception;
        }

        ReflectionSessions reflectionSession;
        try {
            // 트랜잭션 B-성공: 첫 질문과 AI 메타 저장
            reflectionSession =
                    reflectionSessionStartTransactionService
                            .completeFirstQuestion(
                                    context.sessionId(),
                                    context.aiJobId(),
                                    fastApiResponse
                            );
        } catch (RuntimeException exception) {
            // FastAPI 응답값이 비정상이면 작업 이력을 실패로 전환
            reflectionSessionStartTransactionService.failFirstQuestion(
                    context.aiJobId()
            );
            throw exception;
        }

        return ReflectionSessionStartResponseDto.from(
                reflectionSession,
                fastApiResponse
        );
    }

    // FastAPI 첫 질문 생성 실패 후, 질문이 없는 OPEN 세션을 다시 요청
    public ReflectionSessionStartResponseDto retryFirstQuestion(
            Long userId,
            Long sessionId
    ) {
        // 트랜잭션 A: 재시도 QUESTION 작업 이력 저장 후 커밋
        ReflectionSessionStartAiContext context =
                reflectionSessionStartTransactionService
                        .retryFirstQuestion(userId, sessionId);

        FastApiCbtResponseDto fastApiResponse;
        try {
            // 트랜잭션 밖에서 첫 질문 생성 재시도
            fastApiResponse = fastApiCbtClient.start(
                    context.request()
            );
        } catch (ResponseStatusException exception) {
            reflectionSessionStartTransactionService.failFirstQuestion(
                    context.aiJobId()
            );
            throw exception;
        }

        ReflectionSessions reflectionSession;
        try {
            // 트랜잭션 B-성공: 첫 질문과 AI 메타 저장
            reflectionSession =
                    reflectionSessionStartTransactionService
                            .completeFirstQuestion(
                                    context.sessionId(),
                                    context.aiJobId(),
                                    fastApiResponse
                            );
        } catch (RuntimeException exception) {
            reflectionSessionStartTransactionService.failFirstQuestion(
                    context.aiJobId()
            );
            throw exception;
        }

        return ReflectionSessionStartResponseDto.from(
                reflectionSession,
                fastApiResponse
        );
    }

    // 사용자 답변 저장, FastAPI 다음 질문 생성, 결과 저장을 분리해 처리
    public ReflectionSessionTurnResponseDto answerAndContinue(
            Long userId,
            Long sessionId,
            String answer
    ) {
        // 트랜잭션 A: 사용자 답변과 PROCESSING AI 작업 이력 저장 후 커밋
        ReflectionSessionTurnAiContext context =
                reflectionSessionTurnTransactionService
                        .saveAnswerAndStartQuestionJob(
                                userId,
                                sessionId,
                                answer
                        );

        FastApiCbtResponseDto fastApiResponse;
        try {
            // 트랜잭션 밖에서 FastAPI 다음 질문 생성 요청
            fastApiResponse = fastApiCbtClient.turn(
                    context.request()
            );
        } catch (ResponseStatusException exception) {
            // 트랜잭션 B-실패: AI 작업 FAILED 상태 저장
            reflectionSessionTurnTransactionService.failNextQuestion(
                    context.aiJobId()
            );
            throw exception;
        }

        ReflectionSessions reflectionSession;
        try {
            // 트랜잭션 B-성공: 다음 질문 또는 성찰 결과 초안 저장
            reflectionSession =
                    reflectionSessionTurnTransactionService
                            .completeNextQuestion(
                                    context.sessionId(),
                                    context.aiJobId(),
                                    fastApiResponse
                            );
        } catch (RuntimeException exception) {
            reflectionSessionTurnTransactionService.failNextQuestion(
                    context.aiJobId()
            );
            throw exception;
        }

        return ReflectionSessionTurnResponseDto.from(
                reflectionSession,
                fastApiResponse
        );
    }

    // FastAPI 다음 질문 생성 실패 후, 저장된 답변 기준으로 재시도
    public ReflectionSessionTurnResponseDto retryNextQuestion(
            Long userId,
            Long sessionId
    ) {
        // 트랜잭션 A: 재시도 QUESTION 작업 이력 저장 후 커밋
        ReflectionSessionTurnAiContext context =
                reflectionSessionTurnTransactionService
                        .retryNextQuestion(userId, sessionId);

        FastApiCbtResponseDto fastApiResponse;
        try {
            // 트랜잭션 밖에서 다음 질문 생성 재시도
            fastApiResponse = fastApiCbtClient.turn(
                    context.request()
            );
        } catch (ResponseStatusException exception) {
            reflectionSessionTurnTransactionService.failNextQuestion(
                    context.aiJobId()
            );
            throw exception;
        }

        ReflectionSessions reflectionSession;
        try {
            // 트랜잭션 B-성공: 다음 질문 또는 결과 초안 저장
            reflectionSession =
                    reflectionSessionTurnTransactionService
                            .completeNextQuestion(
                                    context.sessionId(),
                                    context.aiJobId(),
                                    fastApiResponse
                            );
        } catch (RuntimeException exception) {
            reflectionSessionTurnTransactionService.failNextQuestion(
                    context.aiJobId()
            );
            throw exception;
        }

        return ReflectionSessionTurnResponseDto.from(
                reflectionSession,
                fastApiResponse
        );
    }

    // CBT 완료 결과를 먼저 저장하고, OpenAI 임베딩은 트랜잭션 밖에서 생성
    public void confirmSession(
            Long userId,
            Long sessionId,
            ReflectionSessionConfirmRequestDto request
    ) {
        ReflectionSessionEmbeddingContext context =
                reflectionSessionEmbeddingTransactionService
                        .confirmAndStartEmbedding(userId, sessionId, request);

        generateAndSaveEmbedding(context);
    }

    // 완료된 CBT 세션의 임베딩 생성에 실패했을 때 벡터 생성만 재시도
    public void retryEmbedding(Long userId, Long sessionId) {
        ReflectionSessionEmbeddingContext context =
                reflectionSessionEmbeddingTransactionService
                        .startEmbeddingRetry(userId, sessionId);

        generateAndSaveEmbedding(context);
    }

    // 외부 OpenAI 임베딩 API 호출은 DB 트랜잭션 밖에서 실행
    private void generateAndSaveEmbedding(
            ReflectionSessionEmbeddingContext context
    ) {
        try {
            float[] contextEmbedding = ragUtils.embed(
                    context.contextEmbeddingText()
            );
            float[] thoughtAwareEmbedding = ragUtils.embed(
                    context.thoughtAwareEmbeddingText()
            );

            reflectionSessionEmbeddingTransactionService.completeEmbedding(
                    context.sessionId(),
                    context.aiJobId(),
                    contextEmbedding,
                    thoughtAwareEmbedding
            );
        } catch (RuntimeException exception) {
            reflectionSessionEmbeddingTransactionService.failEmbedding(
                    context.aiJobId()
            );
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "CBT 임베딩 생성에 실패했습니다."
            );
        }
    }

    // 사용자가 진행중인 CBT 성찰 세션 중단
    @Transactional
    public void cancelSession(
            Long userId,
            Long sessionId
    ) {
        // 중단할 성찰 세션 조회
        ReflectionSessions reflectionSession =
                reflectionSessionsRepository.findById(sessionId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "성찰 세션을 찾을 수 없습니다."
                        ));

        // 다른 사용자가 성찰 세션을 중단하지 못하도록 소유자 확인 -> 404 처리
        if (!reflectionSession.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "성찰 세션을 찾을 수 없습니다."
            );
        }

        // OPEN이 아니면 이미 완료 or 이전에 중단한 세션
        if (reflectionSession.getStatus() != ReflectionSessionStatus.OPEN) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "진행 중인 성찰 세션만 중단할 수 있습니다."
            );
        }

        // Entity의 cancel()이 status와 currentStep을 CANCELLED로 변경
        reflectionSession.cancel();
    }

    // 로그인 사용자가 자신의 CBT 성찰 세션 상세와 질문, 답변 이력 조회
    @Transactional
    public ReflectionSessionDetailResponseDto getSessionDetail(
            Long userId,
            Long sessionId
    ) {
        // 조회할 성찰 세션을 DB에서 찾기
        ReflectionSessions reflectionSession =
                reflectionSessionsRepository.findById(sessionId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "성찰 세션을 찾을 수 없습니다."
                        ));
        // 다른 사용자의 이력 조회 방지
        if (!reflectionSession.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "성찰 세션을 찾을 수 없습니다."
            );
        }

        // Entity 상태와 question_answers JSONB를 DTO로 변환해 반환
        return ReflectionSessionDetailResponseDto.from(
                reflectionSession
        );
    }

    // 로그인 사용자의 진행 중인 OPEN CBT 성찰 세션 목록 조회
    @Transactional
    public List<OpenReflectionSessionResponseDto> getOpenSessions(
            // JWT에서 꺼낸 로그인 사용자 ID
            Long userId
    ){
        // 로그인 사용자가 소유한 OPEN 성찰 세션만 최신순으로 조회
        List<ReflectionSessions> openSessions =
                reflectionSessionsRepository
                        .findAllByUser_IdAndStatusOrderByCreatedAtDesc(
                                userId,
                                ReflectionSessionStatus.OPEN
                        );

        // DB Entity 목록을 프론트에게 줄 DTO 목록으로 변환
        return openSessions.stream()
                .map(OpenReflectionSessionResponseDto::from)
                .toList();
    }

}
