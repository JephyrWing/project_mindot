// 인지왜곡 12개 유형을 관리하는 기준 테이블 Entity
package com.my.mindot_back.distortions.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "distortion_types",

        // category_code로 유형을 분류, 조회할 때 사용할 인덱스
        indexes = {
                @Index(
                        name = "idx_distortion_types_category_code",
                        columnList = "category_code"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DistortionTypes {
    // 인지왜곡 유형 식별자
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 안정적인 인지왜곡 코드
    @Column(nullable = false, unique = true, length = 80)
    private String code;

    // 상위 범주 코드 (테이블 X)
    @Column(name = "category_code", nullable = false, length = 60)
    private String categoryCode;

    // 한글 표시명
    @Column(name = "name_ko", nullable = false, length = 100)
    private String nameKo;

    // 워크시트 영문 명칭
    @Column(name = "name_en", nullable = false, length = 150)
    private String nameEn;

    // 중립적 분류 설명
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    // 표시 순서
    @Column(name = "sort_order", nullable = false, unique = true)
    private Short sortOrder;

    // 사용 여부
    @Column(nullable = false)
    private boolean active = true;

    // 코드셋 근거
    @Column(name = "source_ref", nullable = false, length = 120)
    private String sourceRef = "BECK_TESTING_YOUR_THOUGHTS_2018";

    // 12개 고정 인지왜곡 기준값을 생성하기 위한 생성자
    private DistortionTypes(
            String code,
            String categoryCode,
            String nameKo,
            String nameEn,
            String description,
            short sortOrder
    ) {
        this.code = code;
        this.categoryCode = categoryCode;
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.description = description;
        this.sortOrder = sortOrder;
    }

    public static DistortionTypes create(
            String code,
            String categoryCode,
            String nameKo,
            String nameEn,
            String description,
            short sortOrder
    ) {
        return new DistortionTypes(
                code,
                categoryCode,
                nameKo,
                nameEn,
                description,
                sortOrder
        );
    }
}
