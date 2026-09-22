package com.my.mindot_back.reports.dto;

import java.util.List;

public record MonthlyEmotionCompositionDto(
        int version, String timezone, long recordCount, List<EmotionCountDto> emotions,
        List<Group> days, List<Group> contexts, List<Period> halves
) {
    public record Group(String value, long recordCount, List<EmotionCountDto> emotions) {}
    public record Period(String periodStart, String periodEnd, long recordCount, List<EmotionCountDto> emotions) {}
}
