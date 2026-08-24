package com.my.mindot_back.distortions.config;

import com.my.mindot_back.distortions.repository.DistortionTypesRepository;
import com.my.mindot_back.distortions.entity.DistortionTypes;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
// Bean 설정을 작성하는 설정 클래스
public class DistortionTypesInitializer {
    @Bean
    // 실행 완료 직후, 아래 CommandLineRunner를 한번 실행하도록 등록
    CommandLineRunner initializeDistortionTypes(
            DistortionTypesRepository distortionTypesRepository
    ) {
        return args -> {
            // DB 스키마의 인지왜곡 12개 기준 데이터 저장
            saveIfNotExists(
                    distortionTypesRepository,
                    "ALL_OR_NOTHING_THINKING",
                    "EXTREME_JUDGMENT",
                    "흑백논리",
                    "All-or-nothing thinking",
                    "상황을 연속선이 아닌 두 극단 중 하나로 판단함",
                    (short) 1
            );

            saveIfNotExists(
                    distortionTypesRepository,
                    "CATASTROPHIZING_FORTUNE_TELLING",
                    "UNSUPPORTED_INFERENCE",
                    "파국화·미래예측",
                    "Catastrophizing (fortune telling)",
                    "미래의 부정적 결과를 실제보다 크게 또는 확실하게 예상함",
                    (short) 2
            );

            saveIfNotExists(
                    distortionTypesRepository,
                    "DISQUALIFYING_DISCOUNTING_POSITIVE",
                    "BIASED_EVIDENCE",
                    "긍정적인 면 무시",
                    "Disqualifying or discounting the positive",
                    "긍정적 정보의 의미를 축소하거나 예외로 처리함",
                    (short) 3
            );

            saveIfNotExists(
                    distortionTypesRepository,
                    "EMOTIONAL_REASONING",
                    "EMOTION_RESPONSIBILITY_RULE",
                    "감정적 추론",
                    "Emotional reasoning",
                    "그렇게 느낀다는 이유로 외부 사실도 그렇다고 판단함",
                    (short) 4
            );

            saveIfNotExists(
                    distortionTypesRepository,
                    "LABELING",
                    "EXTREME_JUDGMENT",
                    "낙인찍기",
                    "Labeling",
                    "한 사건이나 행동을 사람 전체에 대한 고정된 평가로 확장함",
                    (short) 5
            );

            saveIfNotExists(
                    distortionTypesRepository,
                    "MAGNIFICATION_MINIMIZATION",
                    "BIASED_EVIDENCE",
                    "과장·축소",
                    "Magnification/minimization",
                    "부정적 요소는 과장하고 긍정적 요소는 축소함",
                    (short) 6
            );

            saveIfNotExists(
                    distortionTypesRepository,
                    "MENTAL_FILTER_SELECTIVE_ABSTRACTION",
                    "BIASED_EVIDENCE",
                    "정신적 여과",
                    "Mental filter (selective abstraction)",
                    "전체 맥락보다 일부 부정적 정보에만 주의를 고정함",
                    (short) 7
            );

            saveIfNotExists(
                    distortionTypesRepository,
                    "MIND_READING",
                    "UNSUPPORTED_INFERENCE",
                    "독심술",
                    "Mind reading",
                    "충분한 근거 없이 타인의 생각이나 의도를 단정함",
                    (short) 8
            );

            saveIfNotExists(
                    distortionTypesRepository,
                    "OVERGENERALIZATION",
                    "EXTREME_JUDGMENT",
                    "과잉일반화",
                    "Overgeneralization",
                    "한두 사건에서 광범위하고 반복적인 결론을 내림",
                    (short) 9
            );

            saveIfNotExists(
                    distortionTypesRepository,
                    "PERSONALIZATION",
                    "EMOTION_RESPONSIBILITY_RULE",
                    "개인화",
                    "Personalization",
                    "여러 원인이 있는 결과를 자신과 과도하게 연결함",
                    (short) 10
            );

            saveIfNotExists(
                    distortionTypesRepository,
                    "SHOULD_MUST_STATEMENTS",
                    "EMOTION_RESPONSIBILITY_RULE",
                    "당위적 사고",
                    "Should and must statements",
                    "자신·타인·상황에 경직된 반드시 또는 해야 한다 규칙을 적용함",
                    (short) 11
            );

            saveIfNotExists(
                    distortionTypesRepository,
                    "TUNNEL_VISION",
                    "BIASED_EVIDENCE",
                    "터널 시야",
                    "Tunnel vision",
                    "한 관점이나 측면만 보고 다른 관련 정보를 배제함",
                    (short) 12
            );
        };
    }
    // 같은 code의 데이터가 없을 때만 새 인지왜곡 유형을 저장하는 공통 메서드
    private void saveIfNotExists(
            DistortionTypesRepository distortionTypesRepository,
            String code,
            String categoryCode,
            String nameKo,
            String nameEn,
            String description,
            short sortOrder
    ) {
        // 앱을 재실행해도 이미 저장된 기준 데이터는 다시 저장하지 않음
        if (distortionTypesRepository.existsByCode(code)) {
            return;
        }

        // Entity의 정적 생성 메서드로 객체를 만들고 DB에 저장
        distortionTypesRepository.save(
                DistortionTypes.create(
                        code,
                        categoryCode,
                        nameKo,
                        nameEn,
                        description,
                        sortOrder
                )
        );
    }
}