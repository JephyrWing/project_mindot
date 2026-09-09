// 감정 기록 목록의 정렬 기준을 정의하는 enum
package com.my.mindot_back.records.dto;

import org.springframework.data.domain.Sort;

public enum EmotionRecordsListSort {
    LATEST(
            Sort.by(
                    Sort.Order.desc("occurredAt"),
                    Sort.Order.desc("id")
            )
    ),
    OLDEST(
            Sort.by(
                    Sort.Order.asc("occurredAt"),
                    Sort.Order.asc("id")
            )
    ),
    INTENSITY_HIGH(
            Sort.by(
                    Sort.Order.desc("primaryIntensity").nullsLast(),
                    Sort.Order.desc("occurredAt"),
                    Sort.Order.desc("id")
            )
    ),
    INTENSITY_LOW(
            Sort.by(
                    Sort.Order.asc("primaryIntensity").nullsLast(),
                    Sort.Order.desc("occurredAt"),
                    Sort.Order.desc("id")
            )
    );

    private final Sort sort;

    EmotionRecordsListSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}