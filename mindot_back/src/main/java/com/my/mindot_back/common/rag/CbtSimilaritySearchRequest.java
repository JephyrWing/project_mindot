package com.my.mindot_back.common.rag;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class CbtSimilaritySearchRequest {
    private Long userId;
    private Long excludeRecordId;
    private String embeddedQueryString;
    private int topK;
    private double threshold;

    public CbtSimilaritySearchRequest(Long userId, Long excludeRecordId, String embeddedQueryString) {
        this(userId, excludeRecordId, embeddedQueryString, 10, 0.7);
    }
}