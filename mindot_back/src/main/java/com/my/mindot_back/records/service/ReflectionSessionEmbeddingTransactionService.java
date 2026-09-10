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
    private final com.my.mindot_back.records.repository.EmotionRecordsRepository records;

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

        long priorId=com.my.mindot_back.records.service.InsightMapping.number(reflectionSession.insight().get("embeddingJobId"));
        if(priorId>0) {
            var prior=aiJobsRepository.findById(priorId).orElse(null);
            if(prior!=null && prior.getStatus()==com.my.mindot_back.ai.entity.AiJobStatus.PROCESSING) {
                if(prior.getAttemptDeadline()==null || prior.getAttemptDeadline().isAfter(java.time.Instant.now()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"검색 연결을 처리하고 있습니다.");
                prior.fail("ATTEMPT_EXPIRED");
            }
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
        var recordId = reflectionSessionsRepository.findRecordIdBySessionId(sessionId).orElse(null);
        if (recordId == null || records.findLockedById(recordId).isEmpty()) return;
        var reflectionSession = reflectionSessionsRepository.findLockedById(sessionId).orElse(null);
        var aiJob = aiJobsRepository.findById(aiJobId).orElse(null);
        if (reflectionSession == null || aiJob == null || aiJob.getStatus() != com.my.mindot_back.ai.entity.AiJobStatus.PROCESSING) return;
        var currentInput = embeddingInput(reflectionSession);
        if (reflectionSession.getStatus() != ReflectionSessionStatus.COMPLETED
                || !Boolean.TRUE.equals(reflectionSession.getUserConfirmed())
                || aiJob.getOperation() != AiJobOperation.EMBED || !aiJob.getEntityId().equals(sessionId)
                || InsightMapping.number(reflectionSession.insight().get("embeddingJobId")) != aiJobId
                || aiJob.getAttemptDeadline() == null || !aiJob.getAttemptDeadline().isAfter(java.time.Instant.now())
                || !currentInput.equals(aiJob.getRequestPayload())) {
            aiJob.fail("STALE_EMBEDDING_RESULT");
            return;
        }
        reflectionSession.applyEmbedding(
                contextEmbedding,
                thoughtAwareEmbedding
        );
        aiJob.complete("text-embedding-3-small", "cbt-embedding-v1");
    }

    // 트랜잭션 B-실패: CBT 완료 결과는 유지하고 임베딩 작업만 실패 처리
    @Transactional
    public void failEmbedding(Long aiJobId) {
        var sessionId = aiJobsRepository.findEntityIdById(aiJobId).orElse(null);
        if (sessionId == null) return;
        var recordId = reflectionSessionsRepository.findRecordIdBySessionId(sessionId).orElse(null);
        if (recordId == null || records.findLockedById(recordId).isEmpty()) return;
        reflectionSessionsRepository.findLockedById(sessionId);
        aiJobsRepository.findById(aiJobId).ifPresent(job -> {
            if (job.getStatus() == com.my.mindot_back.ai.entity.AiJobStatus.PROCESSING) job.fail("EMBEDDING_GENERATION_FAILED");
        });
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
        aiJob.prepareInsight(embeddingInput(reflectionSession),(short)1,java.time.Instant.now().plusSeconds(210));
        aiJobsRepository.saveAndFlush(aiJob);
        aiJob.startProcessing();
        var state=reflectionSession.insight();state.put("embeddingJobId",aiJob.getId());reflectionSession.replaceInsight(state);

        var input = embeddingInput(reflectionSession);
        return new ReflectionSessionEmbeddingContext(reflectionSession.getId(), aiJob.getId(),
                (String)input.get("context"), (String)input.get("thoughtAware"));
    }

    private Map<String,Object> embeddingInput(ReflectionSessions reflectionSession) {
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
                reflectionSession.confirmedBeforeText(),
                reflectionSession.getEmotionRecord().getTimeBucket()
        );

        return Map.of("kind", "EMBED", "context", contextEmbeddingText, "thoughtAware", thoughtAwareEmbeddingText);
    }

    @Transactional
    public Long invalidateForRecord(Long userId, Long recordId) {
        // Caller already holds the record lock. The same order is used by completion.
        var found = reflectionSessionsRepository.findByEmotionRecord_IdAndUser_Id(recordId, userId).orElse(null);
        if (found == null) return null;
        var session = reflectionSessionsRepository.findLockedById(found.getId()).orElseThrow();
        if (session.getStatus() != ReflectionSessionStatus.COMPLETED || !Boolean.TRUE.equals(session.getUserConfirmed())) return null;
        long oldId = InsightMapping.number(session.insight().get("embeddingJobId"));
        aiJobsRepository.findById(oldId).ifPresent(job -> {
            if (job.getStatus() == com.my.mindot_back.ai.entity.AiJobStatus.PROCESSING
                || job.getStatus() == com.my.mindot_back.ai.entity.AiJobStatus.PENDING) job.fail("EMBEDDING_INPUT_CHANGED");
        });
        session.invalidateEmbedding();
        var state = session.insight(); state.remove("embeddingJobId"); session.replaceInsight(state);
        return session.getId();
    }

    private ReflectionSessions findOwnedSession(Long userId, Long sessionId) {
        var recordId = reflectionSessionsRepository.findRecordIdBySessionId(sessionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        records.findLockedById(recordId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        ReflectionSessions reflectionSession = reflectionSessionsRepository
                .findLockedById(sessionId)
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
