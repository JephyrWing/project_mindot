// Spring이 FastAPI의 POST /internal/ai/reflections/start에 보내는 요청 DTO
package com.my.mindot_back.records.dto.ai;

import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.ReflectionSessions;

import java.util.UUID;

public record FastApiCbtStartRequestDto (

    // 요청을 구분하는 중복X 고유 ID
    UUID requestId,

    Long sessionId,

    // 원본 감정 기록에서 꺼낸 CBT 질문 생성용 문맥
    RecordContext record
) {
    // DB에 저장된 성찰 세션과 연결된 감정 기록을 FastAPI 요청 DTO로 변환
    public static FastApiCbtStartRequestDto from(
            ReflectionSessions reflectionSession
    ){
        // 성찰 세션과 연결된 emotion_records Entity
        EmotionRecords emotionRecord =
                reflectionSession.getEmotionRecord();

        return new FastApiCbtStartRequestDto(
                // 요청마다 새 UUID를 만들어 FastAPI 요청을 구분
                UUID.randomUUID(),

                // reflection_sessions.id
                reflectionSession.getId(),

                new RecordContext(
                        emotionRecord.getId(),

                        // 구조화된 객관적 상황
                        emotionRecord.getSituationText(),

                        emotionRecord.getAutomaticThought(),

                        emotionRecord.getPrimaryEmotionCode(),

                        // 감정 강도, 아직 없으면 null 허용
                        emotionRecord.getPrimaryIntensity(),

                        // 자동사고를 사실로 믿는 정도 0~100
                        // null 허용
                        reflectionSession.getBeforeBeliefStrength(),

                        emotionRecord.getContextCategory()
                )
        );
    }
    // FastAPI CbtRecordContext의 record와 같은 구조
    public record RecordContext(


            // emotion_records.id
            Long recordId,

            String situation,

            String automaticThought,

            String primaryEmotionCode,

            Short primaryIntensity,

            Short beforeBeliefStrength,

            String contextCategory
    ){
    }
}
