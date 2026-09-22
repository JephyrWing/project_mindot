package com.my.mindot_back.reports.dto;

// null is missing input; every stored string (including custom names) stays distinct.
public record EmotionCountDto(String emotion, long count) {}
