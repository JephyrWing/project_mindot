// 간편 감정 기록의 저장과 FastAPI AI 구조화 연동을 담당하는 service
package com.my.mindot_back.records.service;

import com.my.mindot_back.ai.entity.AiJobEntityType;

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
import com.my.mindot_back.records.repository.EmotionRecordsSpecifications;
import com.my.mindot_back.records.repository.ReflectionSessionsRepository;
import com.my.mindot_back.records.repository.SessionDistortionsRepository;
import com.my.mindot_back.reports.repository.ReportsRepository;
import com.my.mindot_back.safety.dto.SafetyNoticeResponseDto;
import com.my.mindot_back.safety.service.SafetyEventsService;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EmotionRecordsService {

    // 한번의 목록 요청에서 허용하는 최대 감정 기록의 수
    private static final int MAX_PAGE_SIZE = 50;

    // 감정 기록을 저장하는 Repository
    private final EmotionRecordsRepository emotionRecordsRepository;

    // JWT에서 꺼낸 사용자 ID로 작성자를 조회하는 Repository
    private final UsersRepository usersRepository;

    // Spring > FastAPI 감정 원문 구조화 API 호출
    private final FastApiRecordAnalysisClient  fastApiRecordAnalysisClient;

    // 원문 저장·AI 결과 반영을 각각 독립 트랜잭션으로 처리
    private final EmotionRecordAiTransactionService emotionRecordAiTransactionService;

    // 감정 기록, CBT 세션과 연결된 AI 작업 이력 삭제
    private final AiJobsRepository aiJobsRepository;

    // 감정 기록에 연결된 CBT 성찰 세션 조회
    private final ReflectionSessionsRepository reflectionSessionsRepository;

    // 감정 기록 변경 시 사용자 리포트 캐시 전체를 무효화
    private final ReportsRepository reportsRepository;

    // 완료된 CBT 임베딩을 기반으로 유사사례 검색
    private final RagUtils ragUtils;

    // CBT 세션에 연결된 인지왜곡 라벨 조회
    private final SessionDistortionsRepository sessionDistortionsRepository;

    // 유사 CBT 사례를 바탕으로 FastAPI에 패턴 설명 생성 요청
    private final FastApiPatternExplanationClient fastApiPatternExplanationClient;

    // 감정 기록에 연결된 최신 안전 안내를 조회
    private final SafetyEventsService  safetyEventsService;

    /*
     * 1. 로그인 사용자 확인
     * 2. 원문 감정 기록 생성
     * 3. FastAPI에 원문 전달
     * 4. FastAPI 분석 결과를 Entity에 반영
     * 5. 트랜잭션 성공 시 PostgreSQL에 반영 후 React에 응답
     */
    // 원문 저장, FastAPI 호출, 결과 반영을 분리해 처리
    public EmotionRecordsQuickCreateResponseDto createQuickRecord(
            Long userId,
            EmotionRecordsQuickCreateRequestDto dto
    ) {
        // 트랜잭션 A: 원문 감정 기록과 PROCESSING AI 작업 이력 저장 후 커밋
        EmotionRecordAiJobContext context =
                emotionRecordAiTransactionService
                        .createQuickRecordAndStartAiJob(userId, dto);

        FastApiRecordAnalysisResponseDto analysis;
        try {
            // 트랜잭션 밖에서 FastAPI 호출
            analysis = fastApiRecordAnalysisClient.analyze(
                    context.rawText()
            );
        } catch (ResponseStatusException exception) {
            // 트랜잭션 B-실패: FAILED 작업 이력만 저장
            emotionRecordAiTransactionService.failAiAnalysis(
                    context.aiJobId()
            );
            throw exception;
        }

        // 트랜잭션 B-성공: 구조화 결과와 COMPLETED 상태 저장
        EmotionRecords emotionRecord =
                emotionRecordAiTransactionService.completeAiAnalysis(
                        context.emotionRecordId(),
                        context.aiJobId(),
                        analysis
                );

        // 위험 신호가 감지됐다면 프론트에 표시할 안전 안내 정보 조회
        SafetyNoticeResponseDto safetyNotice =
                safetyEventsService.getLatestSafetyNotice(
                        emotionRecord.getId()
                );

        return EmotionRecordsQuickCreateResponseDto.from(
                emotionRecord,
                analysis,
                safetyNotice
        );
    }

    // QUICK 상태 감정 기록을 다시 FastAPI에 구조화 요청
    public EmotionRecordsDetailResponseDto reanalyzeEmotionRecord(
            Long userId,
            Long emotionRecordId
    ) {
        // 트랜잭션 A: 재처리 작업 이력 생성 후 커밋
        EmotionRecordAiJobContext context =
                emotionRecordAiTransactionService.startReanalysis(
                        userId,
                        emotionRecordId
                );

        FastApiRecordAnalysisResponseDto analysis;
        try {
            // 트랜잭션 밖에서 FastAPI 재분석 호출
            analysis = fastApiRecordAnalysisClient.analyze(
                    context.rawText()
            );
        } catch (ResponseStatusException exception) {
            // 트랜잭션 B-실패: FAILED 상태만 반영
            emotionRecordAiTransactionService.failAiAnalysis(
                    context.aiJobId()
            );
            throw exception;
        }

        // 트랜잭션 B-성공: AI 구조화 결과·COMPLETED 상태 반영
        EmotionRecords emotionRecord =
                emotionRecordAiTransactionService.completeAiAnalysis(
                        context.emotionRecordId(),
                        context.aiJobId(),
                        analysis
                );

        return EmotionRecordsDetailResponseDto.from(
                emotionRecord,
                safetyEventsService.getLatestSafetyNotice(
                        emotionRecord.getId()
                )
        );
    }

    // 기간·감정·상황·원문 검색어·정렬·페이지 조건으로 감정 기록 목록 조회
    public EmotionRecordsPageResponseDto getEmotionRecords(
            Long userId,
            EmotionRecordsListPeriod period,
            String emotionCode,
            String contextCategory,
            String keyword,
            EmotionRecordsListSort sort,
            int page,
            int size
    ) {
        // 페이지 번호는 0부터 시작
        if (page < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "페이지 번호는 0 이상이어야 합니다."
            );
        }

        // 프론트는 size로 페이지당 개수를 바꿀 수 있지만,
        // 한 번에 과도한 데이터를 조회하지 않도록 최대 50개로 제한
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "페이지 크기는 1 이상 50 이하이어야 합니다."
            );
        }

        // 기간 계산에 사용할 현재 사용자의 시간대 조회
        Users user = usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "로그인한 사용자를 찾을 수 없습니다."
                ));

        PeriodRange periodRange = calculatePeriodRange(
                period,
                ZoneId.of(user.getTimezone())
        );

        // 사용자 본인 기록 조건에 선택한 필터 조건을 차례대로 결합
        Specification<EmotionRecords> specification =
                EmotionRecordsSpecifications.ownedBy(userId)
                        .and(
                                EmotionRecordsSpecifications
                                        .hasEmotionCode(emotionCode)
                        )
                        .and(
                                EmotionRecordsSpecifications
                                        .hasContextCategory(contextCategory)
                        )
                        .and(
                                EmotionRecordsSpecifications
                                        .occurredBetween(
                                                periodRange.startInclusive(),
                                                periodRange.endExclusive()
                                        )
                        )
                        .and(
                                EmotionRecordsSpecifications
                                        .containsRawTextKeyword(keyword)
                        );

        // 프론트가 요청한 페이지 번호·크기·정렬 기준으로 DB 조회
        Page<EmotionRecords> emotionRecordsPage =
                emotionRecordsRepository.findAll(
                        specification,
                        PageRequest.of(
                                page,
                                size,
                                sort.toSort()
                        )
                );

        // 목록 내용과 전체 페이지 정보를 함께 반환
        return EmotionRecordsPageResponseDto.from(
                emotionRecordsPage
        );
    }
    // ALL, WEEK, MONTH 선택값을 실제 DB 조회용 시각 범위로 변환
    private PeriodRange calculatePeriodRange(
            EmotionRecordsListPeriod period,
            ZoneId userTimezone
    ) {
        // 사용자의 시간대 기준 오늘 날짜
        LocalDate today = LocalDate.now(userTimezone);

        return switch (period) {
            // 전체는 시작·종료 조건 없이 조회
            case ALL -> new PeriodRange(null, null);

            // 이번 주 월요일 00:00부터 다음 주 월요일 00:00 전까지 조회
            case WEEK -> {
                LocalDate weekStart = today.minusDays(
                        today.getDayOfWeek().getValue() - 1L
                );

                yield new PeriodRange(
                        weekStart.atStartOfDay(userTimezone).toInstant(),
                        weekStart.plusWeeks(1)
                                .atStartOfDay(userTimezone)
                                .toInstant()
                );
            }

            // 이번 달 1일 00:00부터 다음 달 1일 00:00 전까지 조회
            case MONTH -> {
                LocalDate monthStart = today.withDayOfMonth(1);

                yield new PeriodRange(
                        monthStart.atStartOfDay(userTimezone).toInstant(),
                        monthStart.plusMonths(1)
                                .atStartOfDay(userTimezone)
                                .toInstant()
                );
            }
        };
    }

    // 기간 시작은 포함하고 종료는 포함하지 않는 조회 범위 객체
    private record PeriodRange(
            Instant startInclusive,
            Instant endExclusive
    ) {
    }

    // 로그인한 사용자의 감정 기록 상세 조회
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

        // 확정한 감정, 상황, 강도가 기존 주간 리포트 집계에 반영되도록 캐시 무효화
        reportsRepository.deleteByUser_Id(userId);

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

        // 발생 시각 변경으로 주간 리포트 대상 기간이 달라질 수 있어 캐시 무효화
        reportsRepository.deleteByUser_Id(userId);

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

        // 감정 기록 삭제 후 통계·반복 패턴이 오래되지 않도록 리포트 캐시 전체 무효화
        reportsRepository.deleteByUser_Id(userId);

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

                    if(reflectionSession.confirmedInsight()!=null)confirmedDistortionCodes=reflectionSession.confirmedInsightCodes();
                    return new PatternSimilarCaseDto(
                            reflectionSession.getId(),
                            reflectionSession.getEmotionRecord()
                                    .getSituationText(),
                            reflectionSession.confirmedBeforeText(),
                            reflectionSession.getAlternativeThoughtText(),
                            reflectionSession.getHelpfulnessScore(),
                            confirmedDistortionCodes,
                            reflectionSession.confirmedInsight()==null?"legacy":"cbt-insight-1",
                            reflectionSession.confirmedInsight()
                    );
                })

                // Confirmed AFTER eligibility is independent of accepted types.
                .filter(PatternSimilarCaseDto::eligibleForPattern)
                .toList();
    }

    // 현재 감정 기록과 유사한 완료 CBT를 기반으로 패턴 설명
    @Transactional(readOnly = true)
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

        // 확정 AFTER 사례와 구형 수락 유형 사례를 각각의 의미로 조회
        List<PatternSimilarCaseDto> similarCases =
                findPatternSimilarCases(emotionRecord);

        if (similarCases.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "검색에 활용할 수 있는 확정된 유사 CBT 사례가 없습니다."
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
