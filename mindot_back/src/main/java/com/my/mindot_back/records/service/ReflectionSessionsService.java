// CBT 성찰 세션 생성과 FastAPI 질문 생성을 연결하는 Service
package com.my.mindot_back.records.service;

import com.my.mindot_back.distortions.entity.DistortionTypes;
import com.my.mindot_back.distortions.repository.DistortionTypesRepository;
import com.my.mindot_back.records.client.FastApiCbtClient;
import com.my.mindot_back.records.dto.ReflectionSessionStartResponseDto;
import com.my.mindot_back.records.dto.ai.FastApiCbtResponseDto;
import com.my.mindot_back.records.dto.ai.FastApiCbtStartRequestDto;
import com.my.mindot_back.records.entity.EmotionRecords;
import com.my.mindot_back.records.entity.ReflectionSessions;
import com.my.mindot_back.records.entity.SessionDistortions;
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
public class ReflectionSessionsService {

    // 성찰 세션 저장, 조회
    private final ReflectionSessionsRepository reflectionSessionsRepository;

    // 원본 감정 기록 조회
    private final EmotionRecordsRepository emotionRecordsRepository;

    // JWT 사용자 ID로 로그인 사용자 확인
    private final UsersRepository usersRepository;

    // FastAPI CBT 첫 질문 생성 API 호출
    private final FastApiCbtClient fastApiCbtClient;

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
            reflectionSession.addFirstQuestion(
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
        /* addFirstQuestion, applyAiMeta로 변경한 값은
         *트랜잭션 종료 시 JPA Dirty Checking으로 UPDATE
         */

        return ReflectionSessionStartResponseDto.from(
                reflectionSession,
                fastApiResponse
        );
    }
}
