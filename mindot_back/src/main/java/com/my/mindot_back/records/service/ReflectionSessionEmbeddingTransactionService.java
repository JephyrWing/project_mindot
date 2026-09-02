package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.AiJobEntityType;
import com.my.mindot_back.ai.entity.AiJobOperation;
import com.my.mindot_back.ai.entity.AiJobs;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.records.dto.ReflectionSessionConfirmRequestDto;
import com.my.mindot_back.records.entity.DistortionPhase;
import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.entity.SessionDistortions;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.records.repository.SessionDistortionsRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReflectionSessionEmbeddingTransactionService {

    private final ReflectionSessionsRepository reflectionSessionsRepository;
    private final SessionDistortionsRepository sessionDistortionsRepository;
    private final AiJobsRepository aiJobsRepository;

    // 트랜잭션 A: 사용자 확정 결과와 임베딩 작업 이력을 먼저 저장
    @Transactional
    public ReflectionSessionEmbeddingContext confirmAndStartEmbedding(
            Long userId,
            Long sessionId,
            ReflectionSessionConfirmRequestDto request
    ) {
        ReflectionSessions reflectionSession = findOwnedSession(userId, sessionId);

        if (reflectionSession.getStatus() != ReflectionSessionStatus.OPEN) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "진행 중인 성찰 세션이 아닙니다."
            );
        }

        if (!"CONFIRM_REQUIRED".equals(reflectionSession.getCurrentStep())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "아직 최종 확정할 수 있는 단계가 아닙니다."
            );
        }

        applyDistortionReviews(
                sessionId,
                DistortionPhase.BEFORE,
                request.beforeDistortions()
        );
        applyDistortionReviews(
                sessionId,
                DistortionPhase.AFTER,
                request.afterDistortions()
        );

        // 이 시점에 CBT 완료 결과는 외부 임베딩 API 실패와 무관하게 커밋됨
        reflectionSession.confirm(request);

        return createEmbeddingContext(reflectionSession);
    }

    // 트랜잭션 A: 완료됐지만 임베딩이 없는 세션의 임베딩 재시도 작업 생성
    @Transactional
    public ReflectionSessionEmbeddingContext startEmbeddingRetry(
            Long userId,
            Long sessionId
    ) {
        ReflectionSessions reflectionSession = findOwnedSession(userId, sessionId);

        if (reflectionSession.getStatus() != ReflectionSessionStatus.COMPLETED
                || !Boolean.TRUE.equals(reflectionSession.getUserConfirmed())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "완료된 성찰 세션만 임베딩을 재시도할 수 있습니다."
            );
        }

        if (reflectionSession.getContextEmbedding() != null
                && reflectionSession.getThoughtAwareEmbedding() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "이미 임베딩이 생성된 성찰 세션입니다."
            );
        }

        return createEmbeddingContext(reflectionSession);
    }

    // 트랜잭션 B-성공: 외부 임베딩 API가 반환한 두 벡터를 세션에 반영
    @Transactional
    public void completeEmbedding(
            Long sessionId,
            Long aiJobId,
            float[] contextEmbedding,
            float[] thoughtAwareEmbedding
    ) {
        ReflectionSessions reflectionSession = reflectionSessionsRepository
                .findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "성찰 세션을 찾을 수 없습니다."
                ));

        AiJobs aiJob = findAiJob(aiJobId);
        reflectionSession.applyEmbedding(
                contextEmbedding,
                thoughtAwareEmbedding
        );
        aiJob.complete("text-embedding-3-small", "cbt-embedding-v1");
    }

    // 트랜잭션 B-실패: CBT 완료 결과는 유지하고 임베딩 작업만 실패 처리
    @Transactional
    public void failEmbedding(Long aiJobId) {
        findAiJob(aiJobId).fail("EMBEDDING_GENERATION_FAILED");
    }

    private ReflectionSessionEmbeddingContext createEmbeddingContext(
            ReflectionSessions reflectionSession
    ) {
        AiJobs aiJob = AiJobs.create(
                reflectionSession.getUser(),
                AiJobEntityType.REFLECTION,
                reflectionSession.getId(),
                AiJobOperation.EMBED,
                UUID.randomUUID().toString()
        );
        aiJobsRepository.save(aiJob);
        aiJob.startProcessing();

        String contextEmbeddingText = """
                상황 범주: %s
                상황: %s
                감정: %s
                시간 맥락: %s
                """.formatted(
                reflectionSession.getEmotionRecord().getContextCategory(),
                reflectionSession.getEmotionRecord().getSituationText(),
                reflectionSession.getEmotionRecord().getPrimaryEmotionCode(),
                reflectionSession.getEmotionRecord().getTimeBucket()
        );

        String thoughtAwareEmbeddingText = """
                상황 범주: %s
                상황: %s
                감정: %s
                처음 든 생각: %s
                시간 맥락: %s
                """.formatted(
                reflectionSession.getEmotionRecord().getContextCategory(),
                reflectionSession.getEmotionRecord().getSituationText(),
                reflectionSession.getEmotionRecord().getPrimaryEmotionCode(),
                reflectionSession.getEmotionRecord().getAutomaticThought(),
                reflectionSession.getEmotionRecord().getTimeBucket()
        );

        return new ReflectionSessionEmbeddingContext(
                reflectionSession.getId(),
                aiJob.getId(),
                contextEmbeddingText,
                thoughtAwareEmbeddingText
        );
    }

    private ReflectionSessions findOwnedSession(Long userId, Long sessionId) {
        ReflectionSessions reflectionSession = reflectionSessionsRepository
                .findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "성찰 세션을 찾을 수 없습니다."
                ));

        if (!reflectionSession.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "성찰 세션을 찾을 수 없습니다."
            );
        }

        return reflectionSession;
    }

    private AiJobs findAiJob(Long aiJobId) {
        return aiJobsRepository.findById(aiJobId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "AI 작업 이력을 찾을 수 없습니다."
                ));
    }

    private void applyDistortionReviews(
            Long sessionId,
            DistortionPhase phase,
            List<ReflectionSessionConfirmRequestDto.DistortionReviewDto> reviews
    ) {
        List<SessionDistortions> sessionDistortions =
                sessionDistortionsRepository.findAllBySession_IdAndPhase(
                        sessionId,
                        phase
                );

        Map<String, SessionDistortions> distortionsByCode = new HashMap<>();
        for (SessionDistortions sessionDistortion : sessionDistortions) {
            distortionsByCode.put(
                    sessionDistortion.getDistortionType().getCode(),
                    sessionDistortion
            );
        }

        Set<String> reviewedCodes = new HashSet<>();
        for (ReflectionSessionConfirmRequestDto.DistortionReviewDto review : reviews) {
            if (!reviewedCodes.add(review.code())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "같은 인지왜곡 검토 결과가 중복되었습니다."
                );
            }

            SessionDistortions sessionDistortion =
                    distortionsByCode.get(review.code());
            if (sessionDistortion == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "해당 성찰 세션에 없는 인지왜곡입니다."
                );
            }

            sessionDistortion.applyUserReview(review.reviewStatus());
        }
    }
}
