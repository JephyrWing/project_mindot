// FastAPI의 위험 판단 결과를 안전 이벤트로 저장하고 프론트 안내 동작을 결정하는 Service
package com.my.mindot_back.safety.service;

import com.my.mindot_back.ai.entity.AiJobs;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.safety.dto.SafetyNoticeResponseDto;
import com.my.mindot_back.safety.entity.RiskLevel;
import com.my.mindot_back.safety.entity.SafetyActionCode;
import com.my.mindot_back.safety.entity.SafetyEvents;
import com.my.mindot_back.safety.repository.SafetyEventsRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SafetyEventsService {

    // 위험 신호 감지 이력을 safety_events 테이블에 저장
    private final SafetyEventsRepository safetyEventsRepository;

    // FastAPI 위험 수준에 맞는 프론트 안전 안내 동작을 반환하고 이력을 저장
    public SafetyActionCode recordIfRiskDetected(
            EmotionRecords emotionRecord,
            AiJobs aiJob,
            String rawRiskLevel,
            String reasonCode
    ) {
        // 위험 신호가 없으면 안전 이벤트를 만들지 않음
        if (rawRiskLevel == null || "NONE".equals(rawRiskLevel)) {
            return null;
        }

        RiskLevel riskLevel;

        try {
            // FastAPI 문자열 REVIEW / CRISIS를 Spring enum으로 변환
            riskLevel = RiskLevel.valueOf(rawRiskLevel);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "알 수 없는 위험 수준입니다: " + rawRiskLevel
            );
        }

        // 위험 수준에 따라 프론트가 수행할 고정 안내 동작 결정
        SafetyActionCode actionCode =
                riskLevel == RiskLevel.CRISIS
                            ? SafetyActionCode.SHOW_CRISIS_NOTICE
                            : SafetyActionCode.SHOW_REVIEW_NOTICE;

        // 위험 감지 이력을 DB에 저장
        safetyEventsRepository.save(
                SafetyEvents.create(
                        emotionRecord,
                        aiJob,
                        riskLevel,
                        reasonCode,
                        actionCode
                )
        );
        return actionCode;
    }

    // 감정 기록에 연결된 가장 최근 안전 안내를 프론트 응답용 DTO로 조회
    public SafetyNoticeResponseDto getLatestSafetyNotice(
            Long emotionRecordId
    ) {
        return safetyEventsRepository
                .findFirstByEmotionRecords_IdOrderByCreatedAtDesc(
                        emotionRecordId
                )
                .map(SafetyNoticeResponseDto::from)
                .orElse(null);
    }

    // 로그인 사용자가 자신의 안전 안내를 확인한 시각 기록
    @Transactional
    public void markSafetyNoticeShown(
            Long userId,
            Long safetyEventId
    ) {
        // 다른 사용자의 안전 이벤트는 조회, 수정할 수 없도록 사용자 ID까지 함께 확인
        SafetyEvents safetyEvent = safetyEventsRepository
                .findByIdAndEmotionRecords_User_Id(
                        safetyEventId,
                        userId
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "안전 안내 이력을 찾을 수 없습니다."
                ));
        // Entity가 최초 표시 시각만 저장
        safetyEvent.markNoticeShown();
    }
}
