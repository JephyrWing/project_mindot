// 감정 기록 원문의 검색 임베딩을 트랜잭션 밖에서 생성하고 저장하는 Service
package com.my.mindot_back.records.service;

import com.my.mindot_back.common.rag.RagUtils;
import com.my.mindot_back.users.service.ConsentEventsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmotionRecordSearchEmbeddingService {

    private final EmotionRecordSearchEmbeddingTransactionService
            embeddingTransactionService;

    private final RagUtils ragUtils;

    // 비동기 실행 시점에도 AI 분석 동의가 유지되는지 다시 확인
    private final ConsentEventsService consentEventsService;

    // 신규 기록 저장 후 HTTP 응답을 지연시키지 않고 검색 벡터 자동 생성
    @Async
    public void submit(
            Long userId,
            Long emotionRecordId
    ) {
        try {
            generateAndSave(userId, emotionRecordId);
        } catch (RuntimeException ignored) {
            // 실패 상태는 AiJobs에 저장되며 수동 재시도 API로 다시 생성 가능
        }
    }

    // 기존 기록 또는 실패한 기록의 검색 벡터 수동 재생성
    public void retry(
            Long userId,
            Long emotionRecordId
    ) {
        generateAndSave(userId, emotionRecordId);
    }

    // 사용자가 입력한 의미 검색 문장을 일회성 검색 벡터로 변환
    public float[] embedSearchQuery(Long userId, String queryText) {
        consentEventsService.requireAiAnalysisConsent(userId);

        try {
            float[] embedding = ragUtils.embed(queryText);

            if (embedding == null || embedding.length != 1536) {
                throw new IllegalStateException(
                        "검색 쿼리 임베딩 벡터는 1536차원이어야 합니다."
                );
            }

            return embedding;
        } catch (RuntimeException exception) {
            // 검색 문장 내용은 개인정보가 될 수 있으므로 로그에 남기지 않음
            log.error(
                    "감정 기록 의미 검색 쿼리 임베딩 실패: queryLength={}",
                    queryText.length(),
                    exception
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "감정 기록 의미 검색을 처리할 수 없습니다.",
                    exception
            );
        }
    }

    // 외부 OpenAI 호출은 DB 트랜잭션 밖에서 실행
    private void generateAndSave(
            Long userId,
            Long emotionRecordId
    ) {
        consentEventsService.requireAiAnalysisConsent(userId);

        EmotionRecordSearchEmbeddingContext context =
                embeddingTransactionService.start(
                        userId,
                        emotionRecordId
                );

        // 이미 완료됐거나 다른 요청이 처리 중이면 중복 호출하지 않음
        if (!context.dispatch()) {
            return;
        }

        try {
            float[] embedding = ragUtils.embed(context.rawText());

            embeddingTransactionService.complete(
                    userId,
                    context.emotionRecordId(),
                    context.aiJobId(),
                    embedding
            );
        } catch (RuntimeException exception) {
            log.error(
                    "감정 기록 검색 임베딩 생성 실패: userId={}, emotionRecordId={}, aiJobId={}",
                    userId,
                    context.emotionRecordId(),
                    context.aiJobId(),
                    exception
            );

            embeddingTransactionService.fail(
                    userId,
                    context.emotionRecordId(),
                    context.aiJobId(),
                    "RECORD_SEARCH_EMBEDDING_FAILED"
            );
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "감정 기록 검색 임베딩 생성에 실패했습니다.",
                    exception
            );
        }
    }
}
