package com.my.mindot_back.records.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.time.Instant;

// PATCH: omitted fields stay unchanged; explicit null cannot clear a stored value.
public class EmotionRecordsUpdateRequestDto {
    private Instant occurredAt;

    @Size(min = 1, max = 4000)
    private String automaticThought;

    @Size(min = 1)
    private String rawText;

    @jakarta.validation.Valid
    private EmotionRecordsConfirmRequestDto analysis;

    public EmotionRecordsUpdateRequestDto() {}

    public Instant occurredAt() { return occurredAt; }
    public String automaticThought() { return automaticThought; }
    public String rawText() { return rawText; }
    public EmotionRecordsConfirmRequestDto analysis() { return analysis; }

    @JsonSetter(value = "rawText", nulls = Nulls.FAIL)
    public void setRawText(String rawText) { this.rawText = rawText.strip(); }

    @JsonSetter(value = "analysis", nulls = Nulls.FAIL)
    public void setAnalysis(EmotionRecordsConfirmRequestDto analysis) { this.analysis = analysis; }

    @JsonSetter(value = "occurredAt", nulls = Nulls.FAIL)
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    @JsonSetter(value = "automaticThought", nulls = Nulls.FAIL)
    public void setAutomaticThought(String automaticThought) {
        this.automaticThought = automaticThought.strip();
    }

    @JsonIgnore
    @AssertTrue(message = "수정할 항목을 입력해 주세요.")
    public boolean isChangeRequested() {
        return occurredAt != null || automaticThought != null || rawText != null || analysis != null;
    }
}
