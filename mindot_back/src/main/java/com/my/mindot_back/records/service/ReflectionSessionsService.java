// CBT 성찰 세션 생성과 FastAPI 질문 생성을 연결하는 Service
package com.my.mindot_back.records.service;

import com.my.mindot_back.common.rag.RagUtils;
import com.my.mindot_back.distortions.entity.DistortionTypes;
import com.my.mindot_back.distortions.repository.DistortionTypesRepository;
import com.my.mindot_back.records.client.FastApiCbtClient;
import com.my.mindot_back.records.dto.ReflectionSessionConfirmRequestDto;
import com.my.mindot_back.records.dto.ReflectionSessionStartResponseDto;
import com.my.mindot_back.records.dto.ReflectionSessionTurnResponseDto;
import com.my.mindot_back.records.dto.ai.FastApiCbtResponseDto;
import com.my.mindot_back.records.dto.ai.FastApiCbtStartRequestDto;
import com.my.mindot_back.records.dto.ai.FastApiCbtTurnRequestDto;
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

import java.util.*;

@Service
@RequiredArgsConstructor
public class ReflectionSessionsService {

    // 성찰 세션 저장, 조회
    private final ReflectionSessionsRepository reflectionSessionsRepository;

    // 원본 감정 기록 조회
    private final EmotionRecordsRepository emotionRecordsRepository;

    // JWT 사용자 ID로 로그인 사용자 확인
    private final UsersRepository usersRepository;

    // FastAPI CBT 첫 질문 생성 API 호출
    private final FastApiCbtClient fastApiCbtClient;

    // 최종 확정된 CBT 성찰 세션의 RAG 검색용 임베딩 생성
    private final RagUtils ragUtils;

    // FastAPI 인지왜곡 코드로 distortion_types 기준 데이터 조회
    private final DistortionTypesRepository distortionTypesRepository;

    // AI가 제안한 인지왜곡을 session_distortions 테이블에 저장
    private final SessionDistortionsRepository sessionDistortionsRepository;

    // 로그인 사용자의 감정 기록으로 빈 CBT 성찰 세션 생성
    // DB 저장까지만
    @Transactional
    public ReflectionSessions createSession(
            // JWT에서 꺼낸 로그인 사용자 ID
            Long userId,

            // 성찰을 시작할 원본 감정 기록 ID
            Long emotionRecordId
    ) {
        // JWT가 유효해도 예외 상황 대비해 DB에서 확인 (탈퇴 등)
        Users user = usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "사용자를 찾을 수 없습니다."
                ));

        // 성찰의 기반이 되는 원본 감정 기록 조회
        EmotionRecords emotionRecord = emotionRecordsRepository.findById(emotionRecordId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "감정 기록을 찾을 수 없습니다."
                ));


        // FastAPI CBT 시작 요청에서는 자동 사고 필수값
        if (emotionRecord.getAutomaticThought() == null
                     || emotionRecord.getAutomaticThought().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "자동사고가 있는 감정 기록만 성찰을 시작할 수 있습니다."
            );
        }

        // 다른 사용자의 감정 기록에 접근하거나 성찰 세션을 만들 수 없음 -> 404 처리
        if (!emotionRecord.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "감정 기록을 찾을 수 없습니다."
            );
        }

        // 감정 기록 하나에는 성찰 세션 하나만 허용
        if (reflectionSessionsRepository.existsByEmotionRecord_Id(emotionRecordId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "이미 성찰 세션이 생성된 감정 기록입니다."
            );
        }

        // OPEN 상태의 새 성찰 세션 Entity 생성
        ReflectionSessions reflectionSession =
                ReflectionSessions.create(user, emotionRecord);

        // reflection_sessions 테이블에 INSERT 하고 저장된 Entity 반환
        return reflectionSessionsRepository.save(reflectionSession);
    }

    // 성찰 세션 생성하고 FastAPI에 첫 CBT 질문 생성 요청
    @Transactional
    public ReflectionSessionStartResponseDto startSession(
            Long userId,
            Long emotionRecordId
    ){
        // 1. 사용자, 감정 기록 검증 후 OPEN 성찰 세션을 DB에 저장
        ReflectionSessions reflectionSession =
                createSession(userId, emotionRecordId);

        // 2. 저장된 세션과 원본 감정 기록을 FastAPI 요청 JSON으로 변환
        FastApiCbtStartRequestDto request =
                FastApiCbtStartRequestDto.from(reflectionSession);

        // 3. FastAPI에 첫 질문 생성 요청
        FastApiCbtResponseDto fastApiResponse =
                fastApiCbtClient.start(request);

        // 4. 어떤 AI 모델, 프롬프트가 질문을 생성했는지 ai_meta JSONB에 저장
        reflectionSession.applyAiMeta(fastApiResponse.meta());

        // CONTINUE일 때만 실제 질문을 question_answers JSONB에 저장
        // SAFETY_STOP이면 nextQuestion이 없을 수 있으므로 질문 저장 X
        if("CONTINUE".equals((fastApiResponse.status()))){
            reflectionSession.addQuestion(
                    fastApiResponse.nextQuestion()
            );
        }

        // FastAPI가 제안한 성찰 전 인지왜곡을 session_distortions에 저장
        List<FastApiCbtResponseDto.DistortionProposal> proposals =
                fastApiResponse.beforeDistortions();

        if (proposals != null && !proposals.isEmpty()) {
            List<SessionDistortions> sessionDistortions =
                    proposals.stream()
                            .map(proposal -> {
                                DistortionTypes distortionTypes =
                                        distortionTypesRepository
                                                .findByCode(proposal.code())
                                                .orElseThrow(() ->
                                                        new ResponseStatusException(
                                                                HttpStatus.BAD_GATEWAY,
                                                                "AI가 알 수 없는 인지왜곡 코드를 반환했습니다."
                                                        )
                                                );
                                // AI 제안 인지왜곡 Entity 생성
                                return SessionDistortions.createAiProposal(
                                        reflectionSession,
                                        distortionTypes,
                                        proposal.classifierConfidence()
                                );
                            })
                            .toList();
            // session_distortions 테이블에 AI 제안 라벨 일괄 저장
            sessionDistortionsRepository.saveAll(sessionDistortions);
        }

        // reflectionSession은 현재 JPA 관리 상태
        /* addQuestion, applyAiMeta로 변경한 값은
         *트랜잭션 종료 시 JPA Dirty Checking으로 UPDATE
         */

        return ReflectionSessionStartResponseDto.from(
                reflectionSession,
                fastApiResponse
        );
    }

    // 사용자의 현재 답변을 저장하고 FastAPI에 다음 CBT 질문 생성을 요청
    @Transactional
    public ReflectionSessionTurnResponseDto answerAndContinue(
            Long userId,
            Long sessionId,
            String answer
    ){
        // 답변을 저장할 성찰 세션 조회
        ReflectionSessions reflectionSession =
                reflectionSessionsRepository.findById(sessionId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "성찰 세션을 찾을 수 없습니다."
                        ));

        // 다른사용자의 성찰 세션 접근 막음 -> 404
        if(!reflectionSession.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "성찰 세션을 찾을 수 없습니다."
            );
        }

        // 완료, 취소된 세션에는 새 답변이나 질문을 추가할 수 없음
        if (reflectionSession.getStatus() != ReflectionSessionStatus.OPEN) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "진행 중인 성찰 세션이 아닙니다."
            );
        }

        // 현재 질문의 answer, answeredAt을 question_answers JSONB에 먼저 저장
        reflectionSession.answerCurrentQuestion(answer);

        // 성찰 전 단계의 인지왜곡 제안, 검토 정보를 조회
        List<SessionDistortions> beforeDistortions =
                sessionDistortionsRepository.findAllBySession_IdAndPhase(
                        sessionId,
                        DistortionPhase.BEFORE
                );

        // 원본 기록, 전체 질문답변 이력, 인지왜곡 상태를 FastAPI 요청 DTO로 변환
        FastApiCbtTurnRequestDto request =
                FastApiCbtTurnRequestDto.from(
                        reflectionSession,
                        beforeDistortions
                );

        // FastAPI가 다음 질문, 확인 요청, 안전 상태 중 하나를 생성
        FastApiCbtResponseDto fastApiResponse =
                fastApiCbtClient.turn(request);

        // 이번 AI 응답의 모델명, 프롬프트 버전을 최신값으로 저장
        reflectionSession.applyAiMeta(fastApiResponse.meta());

        // FastAPI가 질문 단계를 마치고 성찰 결과 초안을 반환한 경우
        if ("CONFIRM_REQUIRED".equals((fastApiResponse.status()))) {
            // 근거, 대안적 사고 초안을 reflection_sessions에 저장
            // 사용자는 다음 화면에서 이 값 확인 or 수정한 뒤 최종 확정
            reflectionSession.applyOutcomeDraft(
                    fastApiResponse.outcomeDraft()
            );

            // FastAPI 가 제안한 성찰 후 인지왜곡 목록
            List<FastApiCbtResponseDto.DistortionProposal> afterProposals =
                    fastApiResponse.outcomeDraft().afterDistortions();

            // AI가 AFTER 인지왜곡을 제안한 경우에만 저장
            if (afterProposals != null && !afterProposals.isEmpty()) {
                List<SessionDistortions> afterDistortions =
                        afterProposals.stream()
                                .map(proposal -> {
                                    // FastAPI가 보낸 code로 기준 인지왜곡 유형 조회
                                    DistortionTypes distortionTypes =
                                            distortionTypesRepository
                                                    .findByCode(proposal.code())
                                                    .orElseThrow(() ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.BAD_GATEWAY,
                                                                    "AI가 알 수 없는 인지왜곡 코드를 반환했습니다."
                                                            )
                                                    );
                                    // 성찰 후 단계의 AI 제안 라벨 Entity 생성
                                    return SessionDistortions.createAfterAiProposal(
                                            reflectionSession,
                                            distortionTypes,
                                            proposal.classifierConfidence()
                                    );
                                })
                                .toList();

                // DB 저장
                sessionDistortionsRepository.saveAll(afterDistortions);
            }
        }

        // 다음 질문 생성 상태일 때만 question_answers JSONB에 새 질문 추가
        if("CONTINUE".equals((fastApiResponse.status()))){
            reflectionSession.addQuestion(fastApiResponse.nextQuestion());
        }

        // 트랜잭션 종료시 JPA Dirty Checking으로 답변, AI 메타, 다음 질문이 DB에 반영됨
        // 새 AFTER 인지왜곡 라벨은 saveAll()로 저장 대상에 등록됨
        return ReflectionSessionTurnResponseDto.from(
                reflectionSession,
                fastApiResponse
        );
    }

    // 사용자가 성찰 결과와 인지왜곡 검토 결과를 최종 확정
    @Transactional
    public void confirmSession(
            Long userId,
            Long sessionId,
            ReflectionSessionConfirmRequestDto request
    ) {
        // 최종 확정할 성찰 세션 조회
        ReflectionSessions reflectionSession =
                reflectionSessionsRepository.findById(sessionId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "성찰 세션을 찾을 수 없습니다."
                        ));

        // 다른 사용자가 성찰 세션 확정하는 것 방지'
        if (!reflectionSession.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "성찰 세션을 찾을 수 없습니다."
            );
        }

        // OPEN 상태가 아니면 이미 완료 또는 취소된 세션
        if (reflectionSession.getStatus() != ReflectionSessionStatus.OPEN) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "진행 중인 성찰 세션이 아닙니다."
            );
        }

        // FastAPI가 결과 초안을 만들고 사용자에게 확인을 요청한 단계인지 검사
        if (!"CONFIRM_REQUIRED".equals(reflectionSession.getCurrentStep())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "아직 최종 확정할 수 있는 단계가 아닙니다."
            );
        }

        // 사용자가 검토한 성찰 전 인지왜곡 라벨 반영
        applyDistortionReviews(
                sessionId,
                DistortionPhase.BEFORE,
                request.beforeDistortions()
        );

        // 사용자가 검토한 성찰 후 인지왜곡 라벨 반영
        applyDistortionReviews(
                sessionId,
                DistortionPhase.AFTER,
                request.afterDistortions()
        );

        // 사용자가 확인, 수정한 성찰 결과 저장 후 COMPLETED 상태로 완료 처리
        reflectionSession.confirm(request);

        // 최종 확정된 성찰 결과를 이후 유사 CBT 사례 검색에 사용하도록 1536차원 임베딩 생성
        List<float[]> embeddings = ragUtils.CBTEmbed(reflectionSession);

        // RagUtils가 반환한 두 벡터를 reflection_sessions의 vector 컬럼에 반영
        reflectionSession.applyEmbedding(
                embeddings.get(0),
                embeddings.get(1)
        );

        // 조회한 Entity들이므로 save()를 호출하지 않아도 트랜잭션 종료 시
        // Dirty Checking으로 UPDATE됨
    }
    // 사용자가 보낸 인지왜곡 검토 결과를 AI 제안 라벨에 반영
    private void applyDistortionReviews(
            Long sessionId,
            DistortionPhase phase,
            List<ReflectionSessionConfirmRequestDto.DistortionReviewDto> reviews
    ){
        // 해당 성찰 세션과 단계의 기존 AI 제안 레벨을 DB에서 조회
        List<SessionDistortions> sessionDistortions =
                sessionDistortionsRepository.findAllBySession_IdAndPhase(
                        sessionId,
                        phase
                );

        // "인지왜곡 코드 -> DB Entity" 형태로 빠르게 찾을 수 있게 변환
        Map<String, SessionDistortions> distortionByCode =
                new HashMap<>();

        // List 안의 Entity를 하나씩 꺼냄 -> Entity에서 code 꺼냄
        for (SessionDistortions sessionDistortion : sessionDistortions) {
            String code = sessionDistortion.getDistortionType().getCode();
            distortionByCode.put(code, sessionDistortion);
        }
        
        // 같은 코드를 두번 보내는 잘못된 요청 방지
        Set<String> reviewedCodes = new HashSet<>();
        
        for (ReflectionSessionConfirmRequestDto.DistortionReviewDto review : reviews) {
            if (!reviewedCodes.add(review.code())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "같은 인지왜곡 검토 결과가 중복되었습니다."
                );
            }
            
            // 사용자가 보낸 code가 해당 세션의 AI 제안 목록에 실제로 있는지 확인
            SessionDistortions sessionDistortion =
                    distortionByCode.get(review.code());

            if (sessionDistortion == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "해당 성찰 세션에 없는 인지왜곡입니다."
                );
            }

            // 사용자가 선택한 검토 상태, 검토 시각을 Entity에 반영
            sessionDistortion.applyUserReview(review.reviewStatus());
        }
    }
}
