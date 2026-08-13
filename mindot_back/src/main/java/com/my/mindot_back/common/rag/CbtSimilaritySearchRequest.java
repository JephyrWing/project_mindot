package com.my.mindot_back.common.rag;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class CbtSimilaritySearchRequest {
    private Long userId;
    private String embeddedQueryString;
    private int topK;
    private double threshold;

    public CbtSimilaritySearchRequest(Long userId, String embeddedQueryString) {
        this(userId, embeddedQueryString, 10, 0.7);
    }
}