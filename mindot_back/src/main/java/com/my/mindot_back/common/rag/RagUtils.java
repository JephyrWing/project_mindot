package com.my.mindot_back.common.rag;

import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import java.util.Arrays;
import java.util.List;

@RequiredArgsConstructor
public class RagUtils {
    private final EmbeddingModel embeddingModel;
    private final ReflectionSessionsRepository repository;

    public float[] embed(String retrievalText) {
        return embeddingModel.embed(retrievalText);
    }

    // CBT가 완료된 경우, 더 정밀한 RAG를 위해 두 종류로 나눠서 임베딩하여 저장
    public List<float[]> CBTEmbed(ReflectionSessions records) {
        // 처음 든 생각을 빼고 임베딩
        String contextEmbed = """
            상황 범주: %s
            상황: %s
            감정: %s
            시간 맥락: %s
            """.formatted(
                records.getEmotionRecord().getContextCategory(),
                records.getEmotionRecord().getSituationText(),
                records.getEmotionRecord().getPrimaryEmotionCode(),
                records.getEmotionRecord().getTimeBucket()
        );

        // 처음 든 생각을 포함해 임베딩
        String thoughtAwareEmbed = """
            상황 범주: %s
            상황: %s
            감정: %s
            처음 든 생각: %s
            시간 맥락: %s
            """.formatted(
                records.getEmotionRecord().getContextCategory(),
                records.getEmotionRecord().getSituationText(),
                records.getEmotionRecord().getPrimaryEmotionCode(),
                records.getEmotionRecord().getAutomaticThought(),
                records.getEmotionRecord().getTimeBucket()
        );

        float[] contextEmbedding = embed(contextEmbed);
        float[] thoughtAwareEmbedding = embed(thoughtAwareEmbed);
        List<float[]> result = List.of(contextEmbedding, thoughtAwareEmbedding);
        return result;
    }

    // 감정과 상황까지 기록시, 이전 CBT 기록들에서 비슷한 인지왜곡을 일으킨 사례를 찾음
    public List<ReflectionSessions> searchSimilarCases(EmotionRecords records) {
        if (records.getContextCategory() == null ||
                records.getSituationText() == null) {
            return List.of();
        }

        if (records.getAutomaticThought() == null || records.getAutomaticThought().isBlank()) {
            // 처음 든 생각이 기록되지 않은 경우
            String retrievalText = """
            상황 범주: %s
            상황: %s
            감정: %s
            시간 맥락: %s
            """.formatted(
                    records.getContextCategory(),
                    records.getSituationText(),
                    records.getPrimaryEmotionCode(),
                    records.getTimeBucket()
            );
            CbtSimilaritySearchRequest request = new CbtSimilaritySearchRequest(records.getUser().getId(),
                    Arrays.toString(embed(retrievalText)));
            return repository.findSimilarByContext(request);
        }

        // 처음 든 생각이 기록된 경우
        String retrievalText = """
            상황 범주: %s
            상황: %s
            감정: %s
            처음 든 생각: %s
            시간 맥락: %s
            """.formatted(
                records.getContextCategory(),
                records.getSituationText(),
                records.getPrimaryEmotionCode(),
                records.getAutomaticThought(),
                records.getTimeBucket()
        );
        CbtSimilaritySearchRequest request = new CbtSimilaritySearchRequest(records.getUser().getId(),
                Arrays.toString(embed(retrievalText)));
        return repository.findSimilarByThoughtAware(request);
    }
}
