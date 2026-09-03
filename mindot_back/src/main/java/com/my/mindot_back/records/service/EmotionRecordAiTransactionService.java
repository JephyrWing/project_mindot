package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.AiJobEntityType;
import com.my.mindot_back.ai.entity.AiJobOperation;
import com.my.mindot_back.ai.entity.AiJobs;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.records.dto.EmotionRecordsQuickCreateRequestDto;
import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import com.my.mindot_back.records.entity.CompletionStatus;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.safety.service.SafetyEventsService;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmotionRecordAiTransactionService {

    private final EmotionRecordsRepository emotionRecordsRepository;
    private final UsersRepository usersRepository;
    private final AiJobsRepository aiJobsRepository;
    // FastAPI 위험 판단 결과를 safety_events 이력으로 저장
    private final SafetyEventsService safetyEventsService;

    // 트랜잭션 A: 원문 기록과 AI 작업 이력을 먼저 확실히 저장
    @Transactional
    public EmotionRecordAiJobContext createQuickRecordAndStartAiJob(
            Long userId,
            EmotionRecordsQuickCreateRequestDto dto
    ) {
        Users user = usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));

        EmotionRecords emotionRecord = EmotionRecords.createQuick(
                user,
                dto.rawText().trim(),
                dto.inputType(),
                dto.occurredAt()
        );

        EmotionRecords savedEmotionRecord =
                emotionRecordsRepository.save(emotionRecord);

        AiJobs aiJob = AiJobs.create(
                user,
                AiJobEntityType.EMOTION_RECORD,
                savedEmotionRecord.getId(),
                AiJobOperation.STRUCTURE,
                UUID.randomUUID().toString()
        );
        aiJobsRepository.save(aiJob);
        aiJob.startProcessing();

        return new EmotionRecordAiJobContext(
                savedEmotionRecord.getId(),
                aiJob.getId(),
                savedEmotionRecord.getRawText()
        );
    }

    // 트랜잭션 A: 실패 기록 재분석을 위한 AI 작업 이력 생성 후 커밋
    @Transactional
    public EmotionRecordAiJobContext startReanalysis(
            Long userId,
            Long emotionRecordId
    ) {
        EmotionRecords emotionRecord = emotionRecordsRepository
                .findByIdAndUser_Id(emotionRecordId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "감정 기록을 찾을 수 없습니다."
                ));

        // 구조화 실패로 QUICK 상태인 기록만 재분석 가능
        if (emotionRecord.getCompletionStatus() != CompletionStatus.QUICK) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "재분석할 수 있는 감정 기록 상태가 아닙니다."
            );
        }

        AiJobs aiJob = AiJobs.create(
                emotionRecord.getUser(),
                AiJobEntityType.EMOTION_RECORD,
                emotionRecord.getId(),
                AiJobOperation.STRUCTURE,
                UUID.randomUUID().toString()
        );
        aiJobsRepository.save(aiJob);
        aiJob.startProcessing();

        return new EmotionRecordAiJobContext(
                emotionRecord.getId(),
                aiJob.getId(),
                emotionRecord.getRawText()
        );
    }

    // 트랜잭션 B-성공: FastAPI 구조화 결과와 AI 작업 완료 상태 저장
    @Transactional
    public EmotionRecords completeAiAnalysis(
            Long emotionRecordId,
            Long aiJobId,
            FastApiRecordAnalysisResponseDto analysis
    ) {
        EmotionRecords emotionRecord = emotionRecordsRepository
                .findById(emotionRecordId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "감정 기록을 찾을 수 없습니다."
                ));

        AiJobs aiJob = aiJobsRepository.findById(aiJobId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "AI 작업 이력을 찾을 수 없습니다."
                ));

        emotionRecord.applyAiAnalysis(analysis);
        aiJob.complete(
                analysis.meta().model(),
                analysis.meta().promptVersion()
        );

        // REVIEW 또는 CRISIS 위험 신호가 있으면 안전 이벤트 이력 생성
        safetyEventsService.recordIfRiskDetected(
                emotionRecord,
                aiJob,
                analysis.risk() != null
                            ? analysis.risk().level()
                            : null,
                analysis.risk() != null
                            ? analysis.risk().reason()
                            : null
        );

        return emotionRecord;
    }

    // 트랜잭션 B-실패: FastAPI 실패 이력을 저장
    @Transactional
    public void failAiAnalysis(Long aiJobId) {
        AiJobs aiJob = aiJobsRepository.findById(aiJobId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "AI 작업 이력을 찾을 수 없습니다."
                ));

        aiJob.fail("FAST_API_ANALYSIS_FAILED");
    }
}