// 간편 감정 기록 저장 성공 후 react에 보내는 응답 dto
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.entity.EmotionRecords;

import java.time.Instant;

// AI 구조화 전 간편 기록의 저장 결과 전달
// entity 전체 X, 필요한 값만 반환
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

    // 구조화전 -> QUICK
    String completionStatus
){
    // DB에 저장된 EmotionRecords Entity를 프론트 응답용 DTO로 변환
    public static EmotionRecordsQuickCreateResponseDto from(
            EmotionRecords emotionRecords
    ){
        return new EmotionRecordsQuickCreateResponseDto(
                emotionRecords.getId(),
                emotionRecords.getRawText(),
                emotionRecords.getOccurredAt(),
                // enum을 문자열로 바꿈
                emotionRecords.getTimeBucket().name(),
                emotionRecords.getWeekdayType().name(),
                emotionRecords.getCompletionStatus().name()
        );
    }
}
