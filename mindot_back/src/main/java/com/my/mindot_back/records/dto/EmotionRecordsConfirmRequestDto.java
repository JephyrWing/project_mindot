// AI가 구조화한 감정 기록을 사용자가 수정, 확정할 때 받는 요청 DTO
package com.my.mindot_back.records.dto;


import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;
import java.util.Map;

public record EmotionRecordsConfirmRequestDto(

        // AI가 추출한 상황을 사용자가 수정한 값
        String situationText,

        // AI가 추출한 자동적 사고를 사용자가 수정한 값
        String automaticThought,

        // 사용자가 확인한 대표 감정 코드
        String primaryEmotionCode,

        // 사용자가 확인한 대표 감정 강도
        @Min(0)
        @Max(10)
        Short primaryIntensity,

        // 사용자가 확인한 보조 감정 목록
        List<Map<String, Object>> secondaryEmotions,

        // 사용자가 확인한 상황 범주
        String contextCategory,

        // 사용자가 확인한 관계 유형
        String relatedPersonType,

        // 해석, 신체 반응, 행동 등 추가 구조화 정보
        Map<String, Object> details
) {
}