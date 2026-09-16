// 감정 기록 검색 임베딩의 AI 작업 생성·완료·실패와 벡터 저장을 트랜잭션으로 처리
package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.AiJobEntityType;
import com.my.mindot_back.ai.entity.AiJobOperation;
import com.my.mindot_back.ai.entity.AiJobStatus;
import com.my.mindot_back.ai.entity.AiJobs;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
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
public class EmotionRecordSearchEmbeddingTransactionService {

    private static final String EMBEDDING_MODEL = "text-embedding-3-small";
    private static final String EMBEDDING_VERSION =
            "record-search-embedding-v1";

    private final EmotionRecordsRepository emotionRecordsRepository;
    private final AiJobsRepository aiJobsRepository;

    // 검색 벡터가 없는 기록에 새로운 EMBED 작업 생성
    @Transactional
    public EmotionRecordSearchEmbeddingContext start(
            Long userId,
            Long emotionRecordId
    ) {
        EmotionRecords emotionRecord = ownedRecord(
                userId,
                emotionRecordId
        );

        AiJobs previousJob = latestJob(emotionRecord);
        expireIfNecessary(previousJob);

        // 이미 검색 벡터가 있으면 외부 API를 다시 호출하지 않음
        if (emotionRecord.getSearchEmbedding() != null) {
            return context(emotionRecord, previousJob, false);
        }

        // 같은 기록의 임베딩 작업이 진행 중이면 중복 호출하지 않음
        if (isProcessing(previousJob)) {
            return context(emotionRecord, previousJob, false);
        }

        AiJobs newJob = createJob(emotionRecord);

        return context(emotionRecord, newJob, true);
    }

    // 외부 임베딩 호출 성공 결과를 현재 유효한 작업에만 반영
    @Transactional
    public void complete(
            Long userId,
            Long emotionRecordId,
            Long aiJobId,
            float[] embedding
    ) {
        EmotionRecords emotionRecord = ownedRecord(
                userId,
                emotionRecordId
        );

        AiJobs aiJob = aiJobsRepository.findById(aiJobId)
                .orElseThrow(this::missing);

        AiJobs latestJob = latestJob(emotionRecord);
        expireIfNecessary(aiJob);

        // 만료되거나 이미 끝난 작업의 늦은 응답은 반영하지 않음
        if (!isProcessing(aiJob)) {
            return;
        }

        // 다른 사용자·기록·작업의 응답이 현재 기록에 저장되는 것을 차단
        if (aiJob.getEntityType() != AiJobEntityType.EMOTION_RECORD
                || aiJob.getOperation() != AiJobOperation.EMBED
                || !aiJob.getEntityId().equals(emotionRecordId)
                || !aiJob.getUser().getId().equals(userId)
                || latestJob == null
                || !latestJob.getId().equals(aiJobId)) {
            aiJob.fail("STALE_RECORD_EMBEDDING_RESULT");
            return;
        }

        emotionRecord.applySearchEmbedding(embedding);
        aiJob.complete(EMBEDDING_MODEL, EMBEDDING_VERSION);
    }

    // 외부 API 또는 벡터 검증 실패를 AI 작업에 기록
    @Transactional
    public void fail(
            Long userId,
            Long emotionRecordId,
            Long aiJobId,
            String errorCode
    ) {
        EmotionRecords emotionRecord =
                emotionRecordsRepository.findLockedById(emotionRecordId)
                        .orElse(null);

        // 호출 중 기록이 삭제된 경우 늦은 실패 응답을 무시
        if (emotionRecord == null
                || !emotionRecord.getUser().getId().equals(userId)) {
            return;
        }

        aiJobsRepository.findById(aiJobId)
                .filter(this::isProcessing)
                .filter(job ->
                        job.getEntityType()
                                == AiJobEntityType.EMOTION_RECORD
                                && job.getOperation()
                                == AiJobOperation.EMBED
                                && job.getEntityId()
                                .equals(emotionRecordId)
                                && job.getUser().getId()
                                .equals(userId)
                )
                .ifPresent(job -> job.fail(errorCode));
    }

    // 로그인 사용자가 소유한 감정 기록만 잠금 조회
    private EmotionRecords ownedRecord(
            Long userId,
            Long emotionRecordId
    ) {
        return emotionRecordsRepository
                .findLockedById(emotionRecordId)
                .filter(record ->
                        record.getUser().getId().equals(userId)
                )
                .orElseThrow(this::missing);
    }

    // 해당 기록에서 가장 최근 생성된 EMBED 작업 조회
    private AiJobs latestJob(EmotionRecords emotionRecord) {
        return aiJobsRepository
                .findFirstByUser_IdAndEntityTypeAndEntityIdAndOperationOrderByIdDesc(
                        emotionRecord.getUser().getId(),
                        AiJobEntityType.EMOTION_RECORD,
                        emotionRecord.getId(),
                        AiJobOperation.EMBED
                )
                .orElse(null);
    }

    // PENDING 또는 PROCESSING 상태만 외부 응답 반영 가능
    private boolean isProcessing(AiJobs aiJob) {
        return aiJob != null
                && (
                aiJob.getStatus() == AiJobStatus.PENDING
                        || aiJob.getStatus()
                        == AiJobStatus.PROCESSING
        );
    }

    // 작업 제한 시간을 넘겼으면 실패 처리하여 재시도 가능하게 변경
    private void expireIfNecessary(AiJobs aiJob) {
        if (!isProcessing(aiJob)) {
            return;
        }

        Instant deadline = aiJob.getAttemptDeadline() != null
                ? aiJob.getAttemptDeadline()
                : aiJob.getCreatedAt().plusSeconds(60);

        if (!deadline.isAfter(Instant.now())) {
            aiJob.fail("RECORD_EMBEDDING_ATTEMPT_EXPIRED");
        }
    }

    // 감정 기록 검색용 EMBED 작업 생성
    private AiJobs createJob(EmotionRecords emotionRecord) {
        AiJobs aiJob = AiJobs.create(
                emotionRecord.getUser(),
                AiJobEntityType.EMOTION_RECORD,
                emotionRecord.getId(),
                AiJobOperation.EMBED,
                UUID.randomUUID().toString()
        );

        aiJob.prepareInsight(
                Map.of(
                        "kind", "RECORD_SEARCH_EMBEDDING",
                        "embeddingVersion", EMBEDDING_VERSION
                ),
                (short) 1,
                Instant.now().plusSeconds(60)
        );
        aiJob.startProcessing();

        return aiJobsRepository.saveAndFlush(aiJob);
    }

    // 트랜잭션 밖의 임베딩 호출 단계에 필요한 값만 전달
    private EmotionRecordSearchEmbeddingContext context(
            EmotionRecords emotionRecord,
            AiJobs aiJob,
            boolean dispatch
    ) {
        return new EmotionRecordSearchEmbeddingContext(
                emotionRecord.getId(),
                aiJob == null ? null : aiJob.getId(),
                emotionRecord.getRawText(),
                dispatch
        );
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "감정 기록 또는 임베딩 작업을 찾을 수 없습니다."
        );
    }
}