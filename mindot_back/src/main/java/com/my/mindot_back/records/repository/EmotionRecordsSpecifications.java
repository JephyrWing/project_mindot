// 감정 기록 목록의 DB 조회 조건을 조립하는 Specification 모음
package com.my.mindot_back.records.repository;

import com.my.mindot_back.records.entity.EmotionRecords;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Locale;

public final class EmotionRecordsSpecifications {

    private EmotionRecordsSpecifications() {
    }

    // 현재 로그인 사용자가 작성한 감정 기록만 조회하는 조건
    public static Specification<EmotionRecords> ownedBy(Long userId){
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(
                        // emotion_records의 user.id가 현재 로그인 사용자 ID와 같아야 함
                        root.get("user").get("id"),
                        userId
                );
    }

    // 프론트가 선택한 대표 감정 코드와 일치하는 기록만 조회하는 조건
    public static Specification<EmotionRecords> hasEmotionCode(
            String emotionCode
    ) {

        // 감정을 선택하지 않았다면 조건을 추가하지 않고 전체 감정 조회
        if (emotionCode == null || emotionCode.isBlank()) {
            return (root, query, criteriaBuilder) ->
                    criteriaBuilder.conjunction();
        }

        // 프론트가 소문자로 보내도 DB의 대문자 감정코드와 비교하도록 변환
        String normalizedEmotionCode = emotionCode
                .trim()
                .toUpperCase(Locale.ROOT);

        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(
                        // primary_emotion_code = 선택한 감정 코드
                        root.get("primaryEmotionCode"),
                        normalizedEmotionCode
                );
    }

    // 선택한 상황 코드와 일치하는 기록만 조회하는 조건
    public static Specification<EmotionRecords> hasContextCategory(
            String contextCategory
    ) {
        // 상황을 선택하지 않으면 모든 상황 기록을 조회
        if (contextCategory == null || contextCategory.isBlank()) {
            return (root, query, criteriaBuilder) ->
                    criteriaBuilder.conjunction();
        }

        String normalizedContextCategory = contextCategory
                .trim()
                .toUpperCase(Locale.ROOT);

        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(
                        root.get("contextCategory"),
                        normalizedContextCategory
                );
    }

    // 선택한 기간 안에 발생한 기록만 조회하는 조건
    public static Specification<EmotionRecords> occurredBetween(
            Instant startInclusive,
            Instant endExclusive
    ) {
        // 기간 조건이 없으면 전체 기간을 조회
        if (startInclusive == null && endExclusive == null) {
            return (root, query, criteriaBuilder) ->
                    criteriaBuilder.conjunction();
        }

        return (root, query, criteriaBuilder) ->{
            // 시작과 끝이 모두 있으면 [시작, 종료) 범위로 조회
            if (startInclusive != null && endExclusive != null) {
                return criteriaBuilder.and(
                        criteriaBuilder.greaterThanOrEqualTo(
                                root.<Instant>get("occurredAt"),
                                startInclusive
                        ),
                        criteriaBuilder.lessThan(
                                root.<Instant>get("occurredAt"),
                                endExclusive
                        )
                );
            }

            // 시작만 있으면 해당 시각 이후 기록을 조회
            if (startInclusive != null) {
                return criteriaBuilder.greaterThanOrEqualTo(
                        root.<Instant>get("occurredAt"),
                        startInclusive
                );
            }

            // 종료만 있으면 해당 시각 이전 기록을 조회
            return criteriaBuilder.lessThan(
                    root.<Instant>get("occurredAt"),
                    endExclusive
            );
        };
    }

    // 검색어가 사용자가 작성한 원문에 포함된 기록만 조회하는 조건
    public static Specification<EmotionRecords> containsRawTextKeyword(
            String keyword
    ) {
        // 검색어가 없으면 키워드 조건을 추가하지 않음
        if (keyword == null || keyword.isBlank()) {
            return (root, query, criteriaBuilder) ->
                    criteriaBuilder.conjunction();
        }

        // 예: "회의"를 "%회의%"로 바꿔 포함 검색에 사용
        String likeKeyword = "%"
                + escapeLikeKeyword(
                keyword.trim().toLowerCase(Locale.ROOT)
        )
                + "%";

        return (root, query, criteriaBuilder) ->
                criteriaBuilder.like(
                        // 대소문자 차이를 무시하고 rawText 원문만 검색
                        criteriaBuilder.lower(
                                root.<String>get("rawText")
                        ),
                        likeKeyword,
                        '\\'
                );
    }

    // %, _, \ 를 일반 문자로 검색하도록 SQL LIKE 특수문자를 이스케이프
    private static String escapeLikeKeyword(String keyword) {
        return keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
