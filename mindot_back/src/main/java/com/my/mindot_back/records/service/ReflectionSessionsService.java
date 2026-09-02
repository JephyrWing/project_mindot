// CBT 성찰 세션 생성과 FastAPI 질문 생성을 연결하는 Service
package com.my.mindot_back.records.service;

import com.my.mindot_back.common.rag.RagUtils;
import com.my.mindot_back.distortions.repository.DistortionTypesRepository;
import com.my.mindot_back.records.client.FastApiCbtClient;
import com.my.mindot_back.records.dto.*;
import com.my.mindot_back.records.dto.ai.FastApiCbtResponseDto;
import com.my.mindot_back.records.entity.*;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.records.repository.SessionDistortionsRepository;
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

    // 최종 확정된 CBT 성찰 세션의 RAG 검색용 임베딩 생성
    private final RagUtils ragUtils;

    // FastAPI 인지왜곡 코드로 distortion_types 기준 데이터 조회
    private final DistortionTypesRepository distortionTypesRepository;

    // AI가 제안한 인지왜곡을 session_distortions 테이블에 저장
    private final SessionDistortionsRepository sessionDistortionsRepository;

    // CBT 답변 저장과 다음 질문 결과 반영을 독립 트랜잭션으로 처리
    private final ReflectionSessionTurnTransactionService
            reflectionSessionTurnTransactionService;

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

    // 사용자가 성찰 결과와 인지왜곡 검토 결과를 최종 확정
    @Transactional
    public void confirmSession(
            Long userId,
            Long sessionId,
            ReflectionSessionConfirmRequestDto request
    ) {
        // 최종 확정할 성찰 세션 조회
        ReflectionSessions reflectionSession =
                reflectionSessionsRepository.findById(sessionId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "성찰 세션을 찾을 수 없습니다."
                        ));

        // 다른 사용자가 성찰 세션 확정하는 것 방지'
        if (!reflectionSession.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "성찰 세션을 찾을 수 없습니다."
            );
        }

        // OPEN 상태가 아니면 이미 완료 또는 취소된 세션
        if (reflectionSession.getStatus() != ReflectionSessionStatus.OPEN) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "진행 중인 성찰 세션이 아닙니다."
            );
        }

        // FastAPI가 결과 초안을 만들고 사용자에게 확인을 요청한 단계인지 검사
        if (!"CONFIRM_REQUIRED".equals(reflectionSession.getCurrentStep())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "아직 최종 확정할 수 있는 단계가 아닙니다."
            );
        }

        // 사용자가 검토한 성찰 전 인지왜곡 라벨 반영
        applyDistortionReviews(
                sessionId,
                DistortionPhase.BEFORE,
                request.beforeDistortions()
        );

        // 사용자가 검토한 성찰 후 인지왜곡 라벨 반영
        applyDistortionReviews(
                sessionId,
                DistortionPhase.AFTER,
                request.afterDistortions()
        );

        // 사용자가 확인, 수정한 성찰 결과 저장 후 COMPLETED 상태로 완료 처리
        reflectionSession.confirm(request);

        // 최종 확정된 성찰 결과를 이후 유사 CBT 사례 검색에 사용하도록 1536차원 임베딩 생성
        List<float[]> embeddings = ragUtils.CBTEmbed(reflectionSession);

        // RagUtils가 반환한 두 벡터를 reflection_sessions의 vector 컬럼에 반영
        reflectionSession.applyEmbedding(
                embeddings.get(0), // contextEmbedding
                embeddings.get(1)  // thoughtAwareEmbedding
        );

        // 조회한 Entity들이므로 save()를 호출하지 않아도 트랜잭션 종료 시
        // Dirty Checking으로 UPDATE됨
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

    // 사용자가 보낸 인지왜곡 검토 결과를 AI 제안 라벨에 반영
    private void applyDistortionReviews(
            Long sessionId,
            DistortionPhase phase,
            List<ReflectionSessionConfirmRequestDto.DistortionReviewDto> reviews
    ){
        // 해당 성찰 세션과 단계의 기존 AI 제안 레벨을 DB에서 조회
        List<SessionDistortions> sessionDistortions =
                sessionDistortionsRepository.findAllBySession_IdAndPhase(
                        sessionId,
                        phase
                );

        // "인지왜곡 코드 -> DB Entity" 형태로 빠르게 찾을 수 있게 변환
        Map<String, SessionDistortions> distortionByCode =
                new HashMap<>();

        // List 안의 Entity를 하나씩 꺼냄 -> Entity에서 code 꺼냄
        for (SessionDistortions sessionDistortion : sessionDistortions) {
            String code = sessionDistortion.getDistortionType().getCode();
            distortionByCode.put(code, sessionDistortion);
        }
        
        // 같은 코드를 두번 보내는 잘못된 요청 방지
        Set<String> reviewedCodes = new HashSet<>();
        
        for (ReflectionSessionConfirmRequestDto.DistortionReviewDto review : reviews) {
            if (!reviewedCodes.add(review.code())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "같은 인지왜곡 검토 결과가 중복되었습니다."
                );
            }
            
            // 사용자가 보낸 code가 해당 세션의 AI 제안 목록에 실제로 있는지 확인
            SessionDistortions sessionDistortion =
                    distortionByCode.get(review.code());

            if (sessionDistortion == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "해당 성찰 세션에 없는 인지왜곡입니다."
                );
            }

            // 사용자가 선택한 검토 상태, 검토 시각을 Entity에 반영
            sessionDistortion.applyUserReview(review.reviewStatus());
        }
    }
}
