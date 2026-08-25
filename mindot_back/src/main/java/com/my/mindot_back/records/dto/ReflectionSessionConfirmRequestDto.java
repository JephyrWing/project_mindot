// 사용자가 AI 성찰 결과 초안을 확인, 수정한 뒤 최종 확정할 때 보내는 요청 DTO
package com.my.mindot_back.records.dto;

import com.my.mindot_back.records.entity.DistortionReviewStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record ReflectionSessionConfirmRequestDto (

    @Size(max = 4000)
    String evidenceForText,

    @Size(max = 4000)
    String evidenceAgainstText,

    // 사용자가 확인하거나 수정한 대안적 사고
    @NotBlank
    @Size(max = 4000)
    String alternativeThoughtText,

    // 성찰 전 자동사고를 사실로 믿었던 정도: 0~100
    @NotNull
    @Min(0)
    @Max(100)
    Short beforeBeliefStrength,

    // 성찰 후 자동사고를 사실로 믿는 정도: 0~100
    @NotNull
    @Min(0)
    @Max(100)
    Short afterBeliefStrength,

    // 성찰 후 감정 강도: 0~10
    @NotNull
    @Min(0)
    @Max(10)
    Short finalEmotionIntensity,

    // 성찰이 도움이 된 정도: 0~5
    @NotNull
    @Min(0)
    @Max(5)
    Short helpfulnessScore,

    // 성찰 전 인지왜곡 라벨의 사용자 검토 결과
    @NotNull
    @Valid
    @Size(max = 12)
    List<DistortionReviewDto> beforeDistortions,

    // 성찰 후 인지왜곡 라벨의 사용자 검토 결과
    @NotNull
    @Valid
    @Size(max = 12)
    List<DistortionReviewDto> afterDistortions
){
    // 인지왜곡 1건의 코드와 사용자 검토 상태
    public record DistortionReviewDto (

            // distortion_types.code 값
            @NotBlank
            String code,

            // PROPOSED, CONFIRMED, REJECTED 중 사용자가 선택한 상태
            @NotNull
            DistortionReviewStatus reviewStatus
    ) {
    }
}
