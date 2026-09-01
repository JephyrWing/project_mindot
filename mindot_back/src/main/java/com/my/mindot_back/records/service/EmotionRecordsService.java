// 간편 감정 기록의 저장과 FastAPI AI 구조화 연동을 담당하는 service
package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.AiJobEntityType;
import com.my.mindot_back.ai.entity.AiJobOperation;
import com.my.mindot_back.ai.entity.AiJobs;

import java.util.UUID;
import com.my.mindot_back.ai.repository.AiJobsRepository;
import com.my.mindot_back.common.rag.RagUtils;
import com.my.mindot_back.records.client.FastApiPatternExplanationClient;
import com.my.mindot_back.records.client.FastApiRecordAnalysisClient;
import com.my.mindot_back.records.dto.*;
import com.my.mindot_back.records.dto.ai.FastApiPatternExplanationRequestDto;
import com.my.mindot_back.records.dto.ai.FastApiPatternExplanationResponseDto;
import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import com.my.mindot_back.records.entity.*;
import com.my.mindot_back.records.repository.EmotionRecordsRepository;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.records.repository.SessionDistortionsRepository;
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

    // 감정 기록, CBT 세션과 연결된 AI 작업 이력 삭제
    private final AiJobsRepository aiJobsRepository;

    // 감정 기록에 연결된 CBT 성찰 세션 조회
    private final ReflectionSessionsRepository reflectionSessionsRepository;

    // 완료된 CBT 임베딩을 기반으로 유사사례 검색
    private final RagUtils ragUtils;

    // CBT 세션에 연결된 인지왜곡 라벨 조회
    private final SessionDistortionsRepository sessionDistortionsRepository;

    // 유사 CBT 사례를 바탕으로 FastAPI에 패턴 설명 생성 요청
    private final FastApiPatternExplanationClient fastApiPatternExplanationClient;

    /*
     * 1. 로그인 사용자 확인
     * 2. 원문 감정 기록 생성
     * 3. FastAPI에 원문 전달
     * 4. FastAPI 분석 결과를 Entity에 반영
     * 5. 트랜잭션 성공 시 PostgreSQL에 반영 후 React에 응답
     */
    @Transactional(dontRollbackOn = ResponseStatusException.class)
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

        // 감정 기록 구조화 AI 작업 이력 생성
        AiJobs aiJob = AiJobs.create(
                user,
                AiJobEntityType.EMOTION_RECORD,
                savedEmotionRecord.getId(),
                AiJobOperation.STRUCTURE,
                UUID.randomUUID().toString()
        );
        aiJobsRepository.save(aiJob);

        // FastAPI 호출 시작 상태로 변경
        aiJob.startProcessing();

        // Spring > FastAPI: 원문을 보내고 AI 구조화 결과 수신
        // analysis: java record 객체 (record, risk, meta 들어있음)
        FastApiRecordAnalysisResponseDto analysis;
        try {
            // Spring → FastAPI: 원문을 보내고 AI 구조화 결과 수신
            analysis = fastApiRecordAnalysisClient.analyze(
                    savedEmotionRecord.getRawText()
            );
        } catch (ResponseStatusException exception) {
            // FastAPI 통신 실패 시 원문은 남기고 AI 작업만 실패 처리
            aiJob.fail("FAST_API_ANALYSIS_FAILED");
            throw exception;
        }

        /*
         * FastAPI > Spring 결과를 Entity에 반영
         * @Transactional의 JPA dirty checking(jpa가 값 바뀐거 감지)
         * 메서드 종료 시 JPA가 변경된 필드를 UPDATE SQL 실행
         */
        // db에 직접 저장 X, entity 값만 바꿈
        savedEmotionRecord.applyAiAnalysis(analysis);

        // AI 구조화·DB 반영까지 성공한 작업 완료 처리
        aiJob.complete(
                analysis.meta().model(),
                analysis.meta().promptVersion()
        );

        // 원문 저장 결과와 AI 분석 결과를 React 응답 DTO로 변환
        return EmotionRecordsQuickCreateResponseDto.from(
                savedEmotionRecord,
                analysis
        );
    }

    // FastAPI 분석 실패로 QUICK 상태에 남은 감정 기록을 다시 구조화
    @Transactional(dontRollbackOn = ResponseStatusException.class)
    public EmotionRecordsDetailResponseDto reanalyzeEmotionRecord(
            Long userId,
            Long emotionRecordId
    ) {
        // 본인 소유의 감정 기록만 재분석 가능
        EmotionRecords emotionRecord = emotionRecordsRepository
                .findByIdAndUser_Id(emotionRecordId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "감정 기록을 찾을 수 없습니다."
                ));

        // AI 분석에 실패해 QUICK 상태인 기록만 재분석 가능
        if (emotionRecord.getCompletionStatus() != CompletionStatus.QUICK) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "재분석할 수 있는 감정 기록 상태가 아닙니다."
            );
        }

        // 재분석 실행 이력을 새로 생성
        AiJobs aiJob = AiJobs.create(
                emotionRecord.getUser(),
                AiJobEntityType.EMOTION_RECORD,
                emotionRecord.getId(),
                AiJobOperation.STRUCTURE,
                UUID.randomUUID().toString()
        );
        aiJobsRepository.save(aiJob);
        aiJob.startProcessing();

        FastApiRecordAnalysisResponseDto analysis;
        try {
            analysis = fastApiRecordAnalysisClient.analyze(
                    emotionRecord.getRawText()
            );
        } catch (ResponseStatusException exception) {
            // 재시도도 실패하면 실패 이력만 남김
            aiJob.fail("FAST_API_ANALYSIS_FAILED");
            throw exception;
        }

        // AI 구조화 결과 반영 후 작업 완료 처리
        emotionRecord.applyAiAnalysis(analysis);
        aiJob.complete(
                analysis.meta().model(),
                analysis.meta().promptVersion()
        );

        return EmotionRecordsDetailResponseDto.from(emotionRecord);
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

    // 감정 기록 발생 시각 수정
    @Transactional
    public EmotionRecordsDetailResponseDto updateEmotionRecord(
            Long userId,
            Long emotionRecordId,
            EmotionRecordsUpdateRequestDto dto
    ) {
        // 본인 소유의 감정 기록만 조회
        EmotionRecords emotionRecord = emotionRecordsRepository
                .findByIdAndUser_Id(emotionRecordId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "감정 기록을 찾을 수 없습니다."
                ));

        // 발생 시각 수정과 함께 시간대·평일/주말 값 재계산
        emotionRecord.updateOccurredAt(dto.occurredAt());

        // JPA Dirty Checking으로 수정값 저장 후 상세 응답 반환
        return EmotionRecordsDetailResponseDto.from(emotionRecord);
    }

    // 감정 기록과 연결된 파생 데이터를 함께 삭제
    @Transactional
    public void deleteEmotionRecord(
            Long userId,
            Long emotionRecordId
    ) {
        // 본인 소유의 감정 기록만 삭제 가능
        EmotionRecords emotionRecord = emotionRecordsRepository
                .findByIdAndUser_Id(emotionRecordId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "감정 기록을 찾을 수 없습니다."
                ));

        // 연결된 CBT 세션이 있으면 해당 세션의 AI 작업 이력 먼저 삭제
        reflectionSessionsRepository
                .findByEmotionRecord_IdAndUser_Id(emotionRecordId, userId)
                .ifPresent(reflectionSessions ->
                        aiJobsRepository
                                .deleteAllByUser_IdAndEntityTypeAndEntityId(
                                        userId,
                                        AiJobEntityType.REFLECTION,
                                        reflectionSessions.getId()
                                )
                );

        // 감정 기록 자체와 연결된 AI 작업 이력 삭제
        aiJobsRepository.deleteAllByUser_IdAndEntityTypeAndEntityId(
                userId,
                AiJobEntityType.EMOTION_RECORD,
                emotionRecord.getId()
        );

        // DB cascade로 CBT 세션, 인지왜곡 라벨, 안전 이벤트 모두 삭제
        emotionRecordsRepository.delete(emotionRecord);
    }

    // 패턴 분석에 필요한 CBT 데이터가 충분한지 확인
    private void validatePatternAnalysisEligibility(Long userId) {
        // 사용자 최종 확인까지 끝난 CBT가 최소 2개 필요
        long completedSessionCount =
                reflectionSessionsRepository
                        .countByUser_IdAndStatusAndUserConfirmedTrue(
                                userId,
                                ReflectionSessionStatus.COMPLETED
                        );

        if (completedSessionCount < 2) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "패턴 분석에는 확정 완료 CBT 성찰이 최소 2개 필요합니다."
            );
        }

        // 하루 기록이 아닌, 서로 다른 날짜의 기록이 최소 3개 필요
        long distinctDateCount =
                reflectionSessionsRepository
                        .countDistinctCompletedReflectionDates(userId);

        if (distinctDateCount < 3) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "패턴 분석에는 서로 다른 날짜의 감정 기록이 최소 3개 필요합니다."
            );
        }

        // 사용자에게 실제로 도움 되었다고 평가한 사례가 하나 이상 필요
        boolean hasHelpfulSession =
                reflectionSessionsRepository
                        .existsByUser_IdAndStatusAndUserConfirmedTrueAndHelpfulnessScoreGreaterThanEqual(
                                userId,
                                ReflectionSessionStatus.COMPLETED,
                                (short) 3
                        );

        if (!hasHelpfulSession){
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "패턴 분석에는 도움 점수 3점 이상의 확정 완료 CBT 성찰이 필요합니다."
            );
        }
    }

    // 현재 감정 기록과 유사한 완료 CBT를 패턴 분석용 DTO로 변환
    private List<PatternSimilarCaseDto> findPatternSimilarCases(
            EmotionRecords emotionRecord
    ) {
        // 임베딩 유사도 검색으로 과거 완료 CBT 사례 조회
        List<ReflectionSessions> similarSessions =
                ragUtils.searchSimilarCases(emotionRecord);

        // 유사 세션마다 사용자 확정 인지왜곡을 붙여 DTO로 변화
        return similarSessions.stream()
                .map(reflectionSession -> {
                    List<String> confirmedDistortionCodes =
                            sessionDistortionsRepository
                                    .findAllBySession_IdAndPhaseAndReviewStatus(
                                            reflectionSession.getId(),
                                            DistortionPhase.BEFORE,
                                            DistortionReviewStatus.CONFIRMED
                                    )
                                    .stream()
                                    .map(sessionDistortion ->
                                            sessionDistortion
                                                    .getDistortionType()
                                                    .getCode()

                                    )
                                    .toList();

                    return new PatternSimilarCaseDto(
                            reflectionSession.getId(),
                            reflectionSession.getEmotionRecord()
                                    .getSituationText(),
                            reflectionSession.getEmotionRecord()
                                    .getAutomaticThought(),
                            reflectionSession.getAlternativeThoughtText(),
                            reflectionSession.getHelpfulnessScore(),
                            confirmedDistortionCodes
                    );
                })

                // 확정 인지왜곡이 없는 사례는 패턴 근거에서 제외
                .filter(similarCase ->
                        !similarCase.confirmedDistortionCodes().isEmpty()
                )
                .toList();
    }

    // 현재 감정 기록과 유사한 완료 CBT를 기반으로 패턴 설명 생성
    @Transactional
    public PatternExplanationResponseDto explainPattern(
            Long userId,
            Long emotionRecordId
    ) {
        // 본인 소유의 감정 기록만 패턴 분석 가능
        EmotionRecords emotionRecord = emotionRecordsRepository
                .findByIdAndUser_Id(emotionRecordId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "감정 기록을 찾을 수 없습니다."
                ));

        // AI 구조화와 사용자 확정이 끝난 기록만 유사도 검색 가능
        if (emotionRecord.getCompletionStatus()
                != CompletionStatus.COMPLETE) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "구조화 결과가 확정된 감정 기록만 패턴 분석할 수 있습니다."
            );
        }

        // 완료/확정 CBT 수, 날짜 수 ,도움 점수 조건 검사
        validatePatternAnalysisEligibility(userId);

        // 현재 감정 기록과 유사하고 확정 인지왜곡이 있는 과거 CBT 사례 조회
        List<PatternSimilarCaseDto> similarCases =
                findPatternSimilarCases(emotionRecord);

        if (similarCases.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "확정된 인지왜곡을 가진 유사 CBT 사례가 없습니다."
            );
        }

        // 현재 기록과 유사 사례를 FastAPI에 전달해 패턴 설명 생성
        FastApiPatternExplanationResponseDto aiResponse =
                fastApiPatternExplanationClient.explain(
                        new FastApiPatternExplanationRequestDto(
                                emotionRecord.getId(),
                                emotionRecord.getSituationText(),
                                emotionRecord.getAutomaticThought(),
                                emotionRecord.getPrimaryEmotionCode(),
                                similarCases
                        )
                );

        // AI 응답과 실제 활용된 유사 사례 수를 React 응답으로 반환
        return PatternExplanationResponseDto.from(
                emotionRecord.getId(),
                aiResponse,
                similarCases.size()
        );
    }
}
