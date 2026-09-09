package com.my.mindot_back.records.service;
import com.my.mindot_back.ai.entity.*;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.records.dto.*;
import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import com.my.mindot_back.records.entity.*;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.safety.service.SafetyEventsService;
import com.my.mindot_back.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmotionRecordAiTransactionService {
    private final EmotionRecordsRepository emotionRecordsRepository;
    private final UsersRepository usersRepository;
    private final AiJobsRepository aiJobsRepository;
    private final SafetyEventsService safetyEventsService;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional
    public EmotionRecordAiJobContext createQuickRecordAndStartAiJob(
            Long userId, EmotionRecordsQuickCreateRequestDto dto, String key) {
        if (key == null || key.isBlank() || key.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "저장 요청 키가 올바르지 않습니다.");
        // Serialize the logical request lookup before creating any raw record.
        var user = usersRepository.findLockedById(userId).orElseThrow(this::missing);
        String logicalKey = "QUICK:" + key;
        var payload = Map.<String,Object>of("kind", "QUICK_CREATE", "rawText", dto.rawText().trim(),
                "inputType", dto.inputType().name(), "occurredAt", dto.occurredAt().toString());
        var prior = aiJobsRepository.findFirstByUser_IdAndEntityTypeAndOperationAndIdempotencyKeyOrderByIdDesc(
                userId, AiJobEntityType.EMOTION_RECORD, AiJobOperation.STRUCTURE, logicalKey).orElse(null);
        if (prior != null) {
            if (!payload.equals(prior.getRequestPayload()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 저장 요청 키의 내용이 다릅니다.");
            var record = owned(userId, prior.getEntityId());
            entityManager.refresh(prior); // It may have completed while waiting for the record lock.
            expire(prior);
            return context(record, prior, false);
        }
        var record = emotionRecordsRepository.saveAndFlush(EmotionRecords.createQuick(
                user, dto.rawText().trim(), dto.inputType(), dto.occurredAt()));
        return context(record, newJob(record, logicalKey, payload), true);
    }

    @Transactional
    public EmotionRecordAiJobContext startReanalysis(Long userId, Long recordId) {
        var record = owned(userId, recordId);
        if (record.getCompletionStatus() != CompletionStatus.QUICK)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "재분석할 수 있는 감정 기록 상태가 아닙니다.");
        var prior = latest(record);
        expire(prior);
        if (processing(prior)) return context(record, prior, false);
        return context(record, newJob(record, UUID.randomUUID().toString(), Map.of("kind", "REANALYZE")), true);
    }

    @Transactional
    public EmotionRecords completeAiAnalysis(Long recordId, Long jobId, FastApiRecordAnalysisResponseDto analysis) {
        var record = emotionRecordsRepository.findLockedById(recordId).orElseThrow(this::missing);
        var job = aiJobsRepository.findById(jobId).orElseThrow(this::missing);
        var current = latest(record);
        expire(job);
        if (!processing(job)) return record;
        if (job.getEntityType() != AiJobEntityType.EMOTION_RECORD || job.getOperation() != AiJobOperation.STRUCTURE
                || !job.getEntityId().equals(recordId) || !job.getUser().getId().equals(record.getUser().getId())
                || current == null || !current.getId().equals(jobId)
                || record.getCompletionStatus() != CompletionStatus.QUICK) {
            job.fail("STALE_STRUCTURE_RESULT");
            return record;
        }
        record.applyAiAnalysis(analysis);
        job.complete(analysis.meta().model(), analysis.meta().promptVersion());
        safetyEventsService.recordIfRiskDetected(record, job,
                analysis.risk() == null ? null : analysis.risk().level(),
                analysis.risk() == null ? null : analysis.risk().reason());
        return record;
    }

    @Transactional
    public void failAiAnalysis(Long recordId, Long jobId, String code) {
        // Lock order matches completion/deletion; late failures cannot resurrect deleted jobs.
        if (emotionRecordsRepository.findLockedById(recordId).isEmpty()) return;
        aiJobsRepository.findById(jobId).filter(this::processing).ifPresent(job -> job.fail(code));
    }

    @Transactional
    public EmotionRecordsQuickCreateResponseDto savedResponse(Long userId, Long recordId) {
        var record = owned(userId, recordId);
        var job = latest(record);
        expire(job);
        return EmotionRecordsQuickCreateResponseDto.saved(record,
                job == null ? "UNKNOWN" : job.getStatus().name(), job == null ? null : job.getErrorCode(),
                safetyEventsService.getLatestSafetyNotice(recordId));
    }

    private EmotionRecords owned(Long userId, Long recordId) {
        var record = emotionRecordsRepository.findLockedById(recordId).orElseThrow(this::missing);
        if (!record.getUser().getId().equals(userId)) throw missing();
        return record;
    }
    private AiJobs latest(EmotionRecords record) {
        return aiJobsRepository.findFirstByUser_IdAndEntityTypeAndEntityIdAndOperationOrderByIdDesc(
                record.getUser().getId(), AiJobEntityType.EMOTION_RECORD, record.getId(), AiJobOperation.STRUCTURE).orElse(null);
    }
    private boolean processing(AiJobs job) {
        return job != null && (job.getStatus() == AiJobStatus.PROCESSING || job.getStatus() == AiJobStatus.PENDING);
    }
    private void expire(AiJobs job) {
        if (!processing(job)) return;
        Instant deadline = job.getAttemptDeadline() != null ? job.getAttemptDeadline() : job.getCreatedAt().plusSeconds(210);
        if (!deadline.isAfter(Instant.now())) job.fail("ATTEMPT_EXPIRED");
    }
    private AiJobs newJob(EmotionRecords record, String key, Map<String,Object> payload) {
        var job = AiJobs.create(record.getUser(), AiJobEntityType.EMOTION_RECORD, record.getId(), AiJobOperation.STRUCTURE, key);
        job.prepareInsight(payload, (short)1, Instant.now().plusSeconds(210));
        job.startProcessing();
        return aiJobsRepository.saveAndFlush(job);
    }
    private EmotionRecordAiJobContext context(EmotionRecords record, AiJobs job, boolean dispatch) {
        return new EmotionRecordAiJobContext(record.getId(), job.getId(), record.getRawText(), dispatch);
    }
    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "감정 기록 또는 작업을 찾을 수 없습니다.");
    }
}
