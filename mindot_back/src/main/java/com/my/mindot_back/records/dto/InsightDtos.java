package com.my.mindot_back.records.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

public final class InsightDtos {
    private InsightDtos() {}
    public record Open(@Positive Long emotionRecordId, @Positive Long sessionId) {
        @AssertTrue(message="기록 또는 세션 중 하나를 지정해 주세요.")
        public boolean isOneTarget() { return (emotionRecordId==null)!=(sessionId==null); }
    }
    public record Turn(@NotBlank @Size(max=10000) String answer) {}
    public record Review(@NotBlank String code, @NotBlank @Pattern(regexp="CONFIRMED|REJECTED") String reviewStatus) {}
    public record Confirm(
        @NotBlank String proposalId,
        @NotNull @Size(max=12) List<@Valid Review> reviews,
        @NotNull @Min(0) @Max(100) Short beforeBeliefStrength,
        @NotNull @Min(0) @Max(100) Short afterBeliefStrength,
        @NotNull @Min(0) @Max(10) Short finalEmotionIntensity,
        @NotNull @Min(0) @Max(5) Short helpfulnessScore,
        // 두 목록을 모두 보내면 BEFORE/AFTER 라벨 비교 확정 흐름으로 처리한다.
        @Size(max=12) List<@Valid Review> beforeDistortions,
        @Size(max=12) List<@Valid Review> afterDistortions) {}
    public record SessionView(Long sessionId, long revision, String status, String phase,
        Map<String,Object> record, List<Map<String,Object>> messages,
        Map<String,Object> currentProposal, Map<String,Object> confirmedResult,
        Map<String,Object> job, String issue, String resultFormatVersion, Map<String,Object> legacyResult) {}
    public record Prepared(Long sessionId, Long jobId, short attemptNo, long inputRevision,
        Map<String,Object> request, Map<String,Object> restore, boolean start, boolean dispatch, SessionView view) {}
}
