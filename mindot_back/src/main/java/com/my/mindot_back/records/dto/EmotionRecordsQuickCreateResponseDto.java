// 간편 감정 기록 저장 성공 후 react에 보내는 응답 dto
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.safety.dto.SafetyNoticeResponseDto;

import java.time.Instant;

// 원문 저장 결과와 FastAPI 구조화 결과를 함께 전달
// Entity 전체를 노출하지 않고 프론트에 필요한 값만 반환
public record EmotionRecordsQuickCreateResponseDto(

    Long recordId,

    // 사용자가 입력한 감정 원문
    String rawText,

    // 사용자가 감정을 경험한 시각
    Instant occurredAt,

    // 한국 시간으로 계산한 시간
    String timeBucket,

    // 평일, 주말 구분
    String weekdayType,

    // AI 구조화 후 PARTIAL
    String completionStatus,

    // FastAPI가 반환한 상황·감정·자동적 사고 구조화 결과
    FastApiRecordAnalysisResponseDto.StructuredRecord structuredRecord,

    // FastAPI가 반환한 안전 신호 판단 결과
    FastApiRecordAnalysisResponseDto.RiskAssessment risk,

    // AI 모델명과 프롬프트 버전
    FastApiRecordAnalysisResponseDto.AnalysisMeta meta,

    SafetyNoticeResponseDto safetyNotice,
    String analysisStatus,
    String analysisErrorCode
){
    // DB에 저장된 EmotionRecords Entity를 프론트 응답용 DTO로 변환
    public static EmotionRecordsQuickCreateResponseDto from(
            EmotionRecords emotionRecords,
            FastApiRecordAnalysisResponseDto analysis,
            SafetyNoticeResponseDto safetyNotice
    ){
        return new EmotionRecordsQuickCreateResponseDto(
                emotionRecords.getId(),
                emotionRecords.getRawText(),
                emotionRecords.getOccurredAt(),
                // enum을 문자열로 바꿈
                emotionRecords.getTimeBucket().name(),
                emotionRecords.getWeekdayType().name(),
                emotionRecords.getCompletionStatus().name(),
                analysis.record(),
                analysis.risk(),
                analysis.meta(),
                safetyNotice, "COMPLETED", null
        );
    }
    public static EmotionRecordsQuickCreateResponseDto saved(EmotionRecords record, String status, String error,
            SafetyNoticeResponseDto notice) {
        var emotions = new java.util.ArrayList<FastApiRecordAnalysisResponseDto.EmotionItem>();
        if (record.getPrimaryEmotionCode() != null) emotions.add(new FastApiRecordAnalysisResponseDto.EmotionItem(
                record.getPrimaryEmotionCode(), record.getPrimaryIntensity() == null ? null : record.getPrimaryIntensity().intValue()));
        if (record.getSecondaryEmotions() != null) for (var emotion : record.getSecondaryEmotions())
            emotions.add(new FastApiRecordAnalysisResponseDto.EmotionItem((String)emotion.get("code"),
                    emotion.get("intensity") instanceof Number n ? n.intValue() : null));
        var details = record.getDetails() == null ? java.util.Map.<String,Object>of() : record.getDetails();
        var metadata = record.getAiMeta() == null ? java.util.Map.<String,Object>of() : record.getAiMeta();
        var structured = record.getCompletionStatus() == com.my.mindot_back.records.entity.CompletionStatus.QUICK ? null
                : new FastApiRecordAnalysisResponseDto.StructuredRecord(record.getSituationText(), (String)details.get("interpretation"),
                        record.getAutomaticThought(), emotions, (String)details.get("bodyReaction"), (String)details.get("behavior"),
                        record.getContextCategory(), record.getRelatedPersonType());
        return new EmotionRecordsQuickCreateResponseDto(record.getId(), record.getRawText(), record.getOccurredAt(),
                record.getTimeBucket().name(), record.getWeekdayType().name(), record.getCompletionStatus().name(),
                structured, metadata.containsKey("riskLevel") ? new FastApiRecordAnalysisResponseDto.RiskAssessment(
                        (String)metadata.get("riskLevel"), (String)metadata.get("riskReason")) : null,
                metadata.containsKey("model") ? new FastApiRecordAnalysisResponseDto.AnalysisMeta(
                        (String)metadata.get("model"), (String)metadata.get("promptVersion")) : null,
                notice, status, error);
    }
}
