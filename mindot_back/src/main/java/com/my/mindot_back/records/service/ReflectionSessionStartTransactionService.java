package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.AiJobEntityType;
import com.my.mindot_back.ai.entity.AiJobOperation;
import com.my.mindot_back.ai.entity.AiJobs;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.distortions.entity.DistortionTypes;
import com.my.mindot_back.distortions.repository.DistortionTypesRepository;
import com.my.mindot_back.records.dto.ai.FastApiCbtResponseDto;
import com.my.mindot_back.records.dto.ai.FastApiCbtStartRequestDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.ReflectionSessionStatus;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.entity.SessionDistortions;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.records.repository.SessionDistortionsRepository;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReflectionSessionStartTransactionService {

    private final ReflectionSessionsRepository reflectionSessionsRepository;
    private final EmotionRecordsRepository emotionRecordsRepository;
    private final UsersRepository usersRepository;
    private final AiJobsRepository aiJobsRepository;
    private final DistortionTypesRepository distortionTypesRepository;
    private final SessionDistortionsRepository sessionDistortionsRepository;

    // 트랜잭션 A: CBT 세션과 첫 질문 생성 AI 작업 이력을 저장
    @Transactional
    public ReflectionSessionStartAiContext createSessionAndStartQuestionJob(
            Long userId,
            Long emotionRecordId
    ) {
        Users user = usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));

        EmotionRecords emotionRecord = emotionRecordsRepository
                .findById(emotionRecordId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "감정 기록을 찾을 수 없습니다."
                ));

        if (!emotionRecord.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "감정 기록을 찾을 수 없습니다."
            );
        }

        if (emotionRecord.getAutomaticThought() == null
                || emotionRecord.getAutomaticThought().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "자동사고가 있는 감정 기록만 성찰을 시작할 수 있습니다."
            );
        }

        if (reflectionSessionsRepository.existsByEmotionRecord_Id(emotionRecordId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "이미 성찰 세션이 생성된 감정 기록입니다."
            );
        }

        ReflectionSessions reflectionSession =
                reflectionSessionsRepository.save(
                        ReflectionSessions.create(user, emotionRecord)
                );

        AiJobs aiJob = AiJobs.create(
                user,
                AiJobEntityType.REFLECTION,
                reflectionSession.getId(),
                AiJobOperation.QUESTION,
                UUID.randomUUID().toString()
        );
        aiJobsRepository.save(aiJob);
        aiJob.startProcessing();

        return new ReflectionSessionStartAiContext(
                reflectionSession.getId(),
                aiJob.getId(),
                FastApiCbtStartRequestDto.from(reflectionSession)
        );
    }

    // 트랜잭션 B-성공: 첫 질문, AI 메타, 성찰 전 인지왜곡 제안 저장
    @Transactional
    public ReflectionSessions completeFirstQuestion(
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

        if ("CONTINUE".equals(fastApiResponse.status())) {
            reflectionSession.addQuestion(fastApiResponse.nextQuestion());
        }

        List<FastApiCbtResponseDto.DistortionProposal> proposals =
                fastApiResponse.beforeDistortions();

        if (proposals != null && !proposals.isEmpty()) {
            List<SessionDistortions> sessionDistortions =
                    proposals.stream()
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

                                return SessionDistortions.createAiProposal(
                                        reflectionSession,
                                        distortionType,
                                        proposal.classifierConfidence()
                                );
                            })
                            .toList();

            sessionDistortionsRepository.saveAll(sessionDistortions);
        }

        aiJob.complete(
                fastApiResponse.meta().model(),
                fastApiResponse.meta().promptVersion()
        );

        return reflectionSession;
    }

    // 트랜잭션 B-실패: 첫 질문 생성 실패 이력 저장
    @Transactional
    public void failFirstQuestion(Long aiJobId) {
        AiJobs aiJob = aiJobsRepository.findById(aiJobId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "AI 작업 이력을 찾을 수 없습니다."
                ));

        aiJob.fail("FAST_API_CBT_START_FAILED");
    }

    // 트랜잭션 A: 첫 질문 생성에 실패한 OPEN 세션의 재시도 작업 이력 생성
    @Transactional
    public ReflectionSessionStartAiContext retryFirstQuestion(
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

        // OPEN인데 아직 첫 질문이 저장되지 않은 세션만 재시도 가능
        if (reflectionSession.getStatus() != ReflectionSessionStatus.OPEN
                || reflectionSession.getCurrentStep() != null
                || !reflectionSession.getQuestionAnswers().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "첫 질문을 재시도할 수 있는 성찰 세션이 아닙니다."
            );
        }

        AiJobs aiJob = AiJobs.create(
                reflectionSession.getUser(),
                AiJobEntityType.REFLECTION,
                reflectionSession.getId(),
                AiJobOperation.QUESTION,
                UUID.randomUUID().toString()
        );
        aiJobsRepository.save(aiJob);
        aiJob.startProcessing();

        return new ReflectionSessionStartAiContext(
                reflectionSession.getId(),
                aiJob.getId(),
                FastApiCbtStartRequestDto.from(reflectionSession)
        );
    }
}