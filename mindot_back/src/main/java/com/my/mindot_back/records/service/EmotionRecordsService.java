// 간편 감정 기록의 저장과 FastAPI AI 구조화 연동을 담당하는 service
package com.my.mindot_back.records.service;

import com.my.mindot_back.records.client.FastApiRecordAnalysisClient;
import com.my.mindot_back.records.dto.*;
import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import com.my.mindot_back.records.entity.CompletionStatus;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EmotionRecordsService {

    // 감정 기록을 저장하는 Repository
    private final EmotionRecordsRepository emotionRecordsRepository;

    // JWT에서 꺼낸 사용자 ID로 작성자를 조회하는 Repository
    private final UsersRepository usersRepository;

    // Spring > FastAPI 감정 원문 구조화 API 호출
    private final FastApiRecordAnalysisClient  fastApiRecordAnalysisClient;

    /*
     * 1. 로그인 사용자 확인
     * 2. 원문 감정 기록 생성
     * 3. FastAPI에 원문 전달
     * 4. FastAPI 분석 결과를 Entity에 반영
     * 5. 트랜잭션 성공 시 PostgreSQL에 반영 후 React에 응답
     */
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

        // Entity의 정적 생성 메서드로, 아직 DB에 저장되지 않은 JPA 비관리 상태
        // React 요청값으로 AI 분석 전 감정 기록 Entity 생성
        // () : dto가 record이기 때문
        EmotionRecords emotionRecord = EmotionRecords.createQuick(
                user,
                dto.rawText().trim(),
                dto.inputType(),
                dto.occurredAt()
        );

        // repo의 save 호출하면 jpa가 객체 관리
        // 원문 감정 기록을 JPA 관리 상태로 저장
        EmotionRecords savedEmotionRecord =
                emotionRecordsRepository.save(emotionRecord);

        // Spring > FastAPI: 원문을 보내고 AI 구조화 결과 수신
        // analysis: java record 객체 (record, risk, meta 들어있음)
        FastApiRecordAnalysisResponseDto analysis =
                fastApiRecordAnalysisClient.analyze(
                        savedEmotionRecord.getRawText()
                );

        /*
         * FastAPI > Spring 결과를 Entity에 반영
         * @Transactional의 JPA dirty checking(jpa가 값 바뀐거 감지)
         * 메서드 종료 시 JPA가 변경된 필드를 UPDATE SQL 실행
         */
        // db에 직접 저장 X, entity 값만 바꿈
        savedEmotionRecord.applyAiAnalysis(analysis);

        // 원문 저장 결과와 AI 분석 결과를 React 응답 DTO로 변환
        return EmotionRecordsQuickCreateResponseDto.from(
                savedEmotionRecord,
                analysis
        );
    }

    // 로그인한 사용자의 감정 기록을 최신순으로 조회
    @Transactional
    public List<EmotionRecordsListItemResponseDto> getEmotionRecords(
            Long userId
    ) {
        // 해당 사용자의 감정 기록 Entity 목록 조회
        List<EmotionRecords> emotionRecords =
                emotionRecordsRepository
                        .findAllByUser_IdOrderByOccurredAtDesc(userId);

        // Entity 목록을 프론트 응답용 DTO 목록으로 변환
        return emotionRecords.stream()
                .map(EmotionRecordsListItemResponseDto::from)
                .toList();
    }

    // 로그인한 사용자의 감정 기록 상세 조회
    @Transactional
    public EmotionRecordsDetailResponseDto getEmotionRecordsDetail(
            Long userId,
            Long emotionRecordId
    ){
        // 기록 ID와 사용자 ID가 모두 일치하는 기록 조회
        EmotionRecords emotionRecord = emotionRecordsRepository
                .findByIdAndUser_Id(emotionRecordId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "감정 기록을 찾을 수 없습니다."
                ));
        // 조회한 Entity를 상세 응답 DTO로 변환
        return EmotionRecordsDetailResponseDto.from(emotionRecord);
    }

    // 사용자가 AI 구조화 결과를 수정, 확정
    @Transactional
    public EmotionRecordsDetailResponseDto confirmEmotionRecord(
            Long userId,
            Long emotionRecordId,
            EmotionRecordsConfirmRequestDto dto
    ){
        // 본인 소유의 감정 기록만 조회
        EmotionRecords emotionRecord = emotionRecordsRepository
                .findByIdAndUser_Id(emotionRecordId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "감정 기록을 찾을 수 없습니다."
                ));
        // AI 구조화가 끝나 확인 대기 중인 기록만 확정 가능
        if (emotionRecord.getCompletionStatus()
                != CompletionStatus.PARTIAL) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "확정할 수 있는 감정 기록 상태가 아닙니다."
            );
        }
        // 사용자 최종값 반영 후 PARTIAL에서 COMPLETE로 변경
        emotionRecord.confirm(dto);

        // JPA Dirty Checking으로 변경 내용을 저장하고 상세 응답 반환
        return EmotionRecordsDetailResponseDto.from(emotionRecord);
    }
}
