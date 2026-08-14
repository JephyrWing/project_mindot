// 간편 감정 기록의 저장을 담당하는 service
package com.my.mindot_back.records.service;

import com.my.mindot_back.records.dto.EmotionRecordsQuickCreateRequestDto;
import com.my.mindot_back.records.dto.EmotionRecordsQuickCreateResponseDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class EmotionRecordsService {

    // 감정 기록을 저장하는 Repository
    private final EmotionRecordsRepository emotionRecordsRepository;

    // JWT에서 꺼낸 사용자 ID로 작성자를 조회하는 Repository
    private final UsersRepository usersRepository;

    // 간편 감정 기록 원문 저장
    // userId는 Controller 가 JWT 인증 정보를 통해 전달
    // dto: 프론트 요청, rawText, inputType, occurredAt
    @Transactional
    public EmotionRecordsQuickCreateResponseDto createQuickRecord(
            Long userId,
            EmotionRecordsQuickCreateRequestDto dto
    ) {
        // JWT는 유효하지만, 사용자가 삭제된 예외 상황 대비 -> DB 에서 사용자 확인
        Users user = usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));

        // Entity의 정적 생성 매서드
        // AI 구조화 전 원문 기록 생성
        // () : dto가 record이기 때문
        EmotionRecords emotionRecord = EmotionRecords.createQuick(
                user,
                dto.rawText().trim(),
                dto.inputType(),
                dto.occurredAt()
        );

        // DB에 저장한 entity를 프론트 응답 dto로 변환해 반환
        EmotionRecords savedEmotionRecord =
                emotionRecordsRepository.save(emotionRecord);
        return EmotionRecordsQuickCreateResponseDto.from(savedEmotionRecord);
    }
}
