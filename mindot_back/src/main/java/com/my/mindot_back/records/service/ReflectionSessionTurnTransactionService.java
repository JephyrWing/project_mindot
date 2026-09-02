package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.AiJobEntityType;
import com.my.mindot_back.ai.entity.AiJobOperation;
import com.my.mindot_back.ai.entity.AiJobs;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.distortions.entity.DistortionTypes;
import com.my.mindot_back.distortions.repository.DistortionTypesRepository;
import com.my.mindot_back.records.dto.ai.FastApiCbtResponseDto;
import com.my.mindot_back.records.dto.ai.FastApiCbtTurnRequestDto;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReflectionSessionTurnTransactionService {

    private final ReflectionSessionsRepository reflectionSessionsRepository;
    private final SessionDistortionsRepository sessionDistortionsRepository;
    private final AiJobsRepository aiJobsRepository;
    private final DistortionTypesRepository distortionTypesRepository;

    // 트랜잭션 A: 사용자 답변과 다음 질문 생성 AI 작업 이력 저장
    @Transactional
    public ReflectionSessionTurnAiContext saveAnswerAndStartQuestionJob(
            Long userId,
            Long sessionId,
            String answer
    ) {
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

        if (reflectionSession.getStatus() != ReflectionSessionStatus.OPEN) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "진행 중인 성찰 세션이 아닙니다."
            );
        }

        // 이 트랜잭션이 커밋되면 FastAPI 실패와 무관하게 답변은 보존됨
        reflectionSession.answerCurrentQuestion(answer);

        List<SessionDistortions> beforeDistortions =
                sessionDistortionsRepository.findAllBySession_IdAndPhase(
                        sessionId,
                        DistortionPhase.BEFORE
                );

        AiJobs aiJob = AiJobs.create(
                reflectionSession.getUser(),
                AiJobEntityType.REFLECTION,
                reflectionSession.getId(),
                AiJobOperation.QUESTION,
                UUID.randomUUID().toString()
        );
        aiJobsRepository.save(aiJob);
        aiJob.startProcessing();

        return new ReflectionSessionTurnAiContext(
                reflectionSession.getId(),
                aiJob.getId(),
                FastApiCbtTurnRequestDto.from(
                        reflectionSession,
                        beforeDistortions
                )
        );
    }

    // 트랜잭션 B-성공: 다음 질문 또는 성찰 결과 초안과 AI 작업 완료 상태 저장
    @Transactional
    public ReflectionSessions completeNextQuestion(
            Long sessionId,
            Long aiJobId,
            FastApiCbtResponseDto fastApiResponse
    ) {
        ReflectionSessions reflectionSession = reflectionSessionsRepository
                .findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "성찰 세션을 찾을 수 없습니다."
                ));

        AiJobs aiJob = aiJobsRepository.findById(aiJobId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "AI 작업 이력을 찾을 수 없습니다."
                ));

        reflectionSession.applyAiMeta(fastApiResponse.meta());

        if ("CONFIRM_REQUIRED".equals(fastApiResponse.status())) {
            reflectionSession.applyOutcomeDraft(
                    fastApiResponse.outcomeDraft()
            );

            List<FastApiCbtResponseDto.DistortionProposal> afterProposals =
                    fastApiResponse.outcomeDraft().afterDistortions();

            if (afterProposals != null && !afterProposals.isEmpty()) {
                List<SessionDistortions> afterDistortions =
                        afterProposals.stream()
                                .map(proposal -> {
                                    DistortionTypes distortionType =
                                            distortionTypesRepository
                                                    .findByCode(proposal.code())
                                                    .orElseThrow(() ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.BAD_GATEWAY,
                                                                    "AI가 알 수 없는 인지왜곡 코드를 반환했습니다."
                                                            )
                                                    );

                                    return SessionDistortions
                                            .createAfterAiProposal(
                                                    reflectionSession,
                                                    distortionType,
                                                    proposal.classifierConfidence()
                                            );
                                })
                                .toList();

                sessionDistortionsRepository.saveAll(afterDistortions);
            }
        }

        if ("CONTINUE".equals(fastApiResponse.status())) {
            reflectionSession.addQuestion(
                    fastApiResponse.nextQuestion()
            );
        }

        aiJob.complete(
                fastApiResponse.meta().model(),
                fastApiResponse.meta().promptVersion()
        );

        return reflectionSession;
    }

    // 트랜잭션 B-실패: 다음 질문 생성 실패 이력 저장
    @Transactional
    public void failNextQuestion(Long aiJobId) {
        AiJobs aiJob = aiJobsRepository.findById(aiJobId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "AI 작업 이력을 찾을 수 없습니다."
                ));

        aiJob.fail("FAST_API_CBT_TURN_FAILED");
    }

    // 트랜잭션 A: 답변은 이미 저장됐지만 다음 질문 생성에 실패한 세션 재시도 준비
    @Transactional
    public ReflectionSessionTurnAiContext retryNextQuestion(
            Long userId,
            Long sessionId
    ) {
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

        if (reflectionSession.getStatus() != ReflectionSessionStatus.OPEN
                || reflectionSession.getCurrentStep() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "다음 질문을 재시도할 수 있는 성찰 세션이 아닙니다."
            );
        }

        Map<String, Object> currentQuestion = reflectionSession
                .getQuestionAnswers()
                .stream()
                .filter(question -> reflectionSession.getCurrentStep()
                        .equals(question.get("questionCode")))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "재시도할 현재 질문이 없습니다."
                ));

        // 현재 질문의 답변이 이미 저장된 실패 상황만 재시도 가능
        if (currentQuestion.get("answer") == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "저장된 답변이 없어 다음 질문을 재시도할 수 없습니다."
            );
        }

        List<SessionDistortions> beforeDistortions =
                sessionDistortionsRepository.findAllBySession_IdAndPhase(
                        sessionId,
                        DistortionPhase.BEFORE
                );

        AiJobs aiJob = AiJobs.create(
                reflectionSession.getUser(),
                AiJobEntityType.REFLECTION,
                reflectionSession.getId(),
                AiJobOperation.QUESTION,
                UUID.randomUUID().toString()
        );
        aiJobsRepository.save(aiJob);
        aiJob.startProcessing();

        return new ReflectionSessionTurnAiContext(
                reflectionSession.getId(),
                aiJob.getId(),
                FastApiCbtTurnRequestDto.from(
                        reflectionSession,
                        beforeDistortions
                )
        );
    }
}