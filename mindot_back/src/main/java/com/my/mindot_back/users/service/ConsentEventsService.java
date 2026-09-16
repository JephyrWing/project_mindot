// 일반·소셜 회원가입의 필수 동의 이력 3건을 동일한 버전으로 저장하는 Service
package com.my.mindot_back.users.service;

import com.my.mindot_back.users.entity.ConsentEvents;
import com.my.mindot_back.users.entity.ConsentAction;
import com.my.mindot_back.users.entity.ConsentType;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.dto.ConsentHistoryItemResponseDto;
import com.my.mindot_back.users.dto.ConsentHistoryPageResponseDto;
import com.my.mindot_back.users.dto.ConsentStatusResponseDto;
import com.my.mindot_back.users.repository.ConsentEventsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConsentEventsService {

    private static final String TERMS_VERSION = "terms-v1";
    private static final String PRIVACY_VERSION = "privacy-v1";
    private static final String AI_ANALYSIS_VERSION = "ai-analysis-v1";
    private static final String COUNSELOR_SHARE_VERSION =
            "counselor-share-v1";

    private final ConsentEventsRepository consentEventsRepository;
    private final UsersRepository usersRepository;

    /*
     * 사용자 저장과 동의 이력 저장이 하나의 DB 트랜잭션에서 처리되도록
     * 반드시 이미 시작된 회원가입 트랜잭션 안에서만 실행
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void grantRequiredConsents(Users user) {
        List<ConsentEvents> consentEvents = List.of(
                ConsentEvents.grant(
                        user,
                        ConsentType.TERMS,
                        TERMS_VERSION
                ),
                ConsentEvents.grant(
                        user,
                        ConsentType.PRIVACY,
                        PRIVACY_VERSION
                ),
                ConsentEvents.grant(
                        user,
                        ConsentType.AI_ANALYSIS,
                        AI_ANALYSIS_VERSION
                )
        );

        consentEventsRepository.saveAll(consentEvents);
    }

    // 로그인 사용자의 동의 종류별 최신 상태 조회
    @Transactional(readOnly = true)
    public List<ConsentStatusResponseDto> getCurrentConsents(
            Long userId
    ) {
        requireUser(userId);

        return Arrays.stream(ConsentType.values())
                .map(consentType -> toStatus(
                        consentType,
                        latestEvent(userId, consentType)
                ))
                .toList();
    }

    // 로그인 사용자의 전체 동의 변경 이력을 최신순으로 페이징 조회
    @Transactional(readOnly = true)
    public ConsentHistoryPageResponseDto getConsentHistory(
            Long userId,
            int page,
            int size
    ) {
        if (page < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "페이지 번호는 0 이상이어야 합니다."
            );
        }

        if (size < 1 || size > 50) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "페이지 크기는 1 이상 50 이하이어야 합니다."
            );
        }

        requireUser(userId);

        return ConsentHistoryPageResponseDto.from(
                consentEventsRepository
                        .findAllByUser_IdOrderByOccurredAtDescIdDesc(
                                userId,
                                PageRequest.of(page, size)
                        )
                        .map(ConsentHistoryItemResponseDto::from)
        );
    }

    // 변경 가능한 동의를 철회하거나 현재 버전으로 다시 동의
    @Transactional
    public ConsentStatusResponseDto changeConsent(
            Long userId,
            ConsentType consentType,
            boolean granted
    ) {
        if (!isChangeable(consentType)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "이용약관과 개인정보 처리는 회원 탈퇴 없이 개별 철회할 수 없습니다."
            );
        }

        Users user = usersRepository.findLockedById(userId)
                .orElseThrow(this::userNotFound);

        ConsentEvents latest = latestEvent(userId, consentType);
        String currentVersion = versionOf(consentType);

        // 동의하지 않은 항목을 다시 철회하면 이력을 중복 생성하지 않음
        if (!granted && (latest == null
                || latest.getAction() == ConsentAction.REVOKED)) {
            return toStatus(consentType, latest);
        }

        // 현재 버전에 이미 동의했다면 같은 GRANTED 이력을 중복 생성하지 않음
        if (granted
                && latest != null
                && latest.getAction() == ConsentAction.GRANTED
                && currentVersion.equals(latest.getConsentVersion())) {
            return toStatus(consentType, latest);
        }

        ConsentEvents changedEvent = granted
                ? ConsentEvents.grant(
                        user,
                        consentType,
                        currentVersion
                )
                : ConsentEvents.revoke(
                        user,
                        consentType,
                        latest.getConsentVersion()
                );

        return toStatus(
                consentType,
                consentEventsRepository.save(changedEvent)
        );
    }

    // 외부 AI 처리를 시작하기 전에 현재 AI 분석 동의 상태 확인
    @Transactional(readOnly = true)
    public void requireAiAnalysisConsent(Long userId) {
        ConsentEvents latest = latestEvent(
                userId,
                ConsentType.AI_ANALYSIS
        );

        if (latest == null
                || latest.getAction() != ConsentAction.GRANTED
                || !AI_ANALYSIS_VERSION.equals(
                        latest.getConsentVersion()
                )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "AI 분석 동의가 필요합니다."
            );
        }
    }

    private ConsentEvents latestEvent(
            Long userId,
            ConsentType consentType
    ) {
        return consentEventsRepository
                .findFirstByUser_IdAndConsentTypeOrderByOccurredAtDescIdDesc(
                        userId,
                        consentType
                )
                .orElse(null);
    }

    private ConsentStatusResponseDto toStatus(
            ConsentType consentType,
            ConsentEvents event
    ) {
        ConsentAction latestAction = event == null
                ? null
                : event.getAction();
        String currentVersion = versionOf(consentType);

        return new ConsentStatusResponseDto(
                consentType,
                currentVersion,
                latestAction,
                latestAction == ConsentAction.GRANTED
                        && currentVersion.equals(
                                event.getConsentVersion()
                        ),
                isChangeable(consentType),
                event == null ? null : event.getOccurredAt()
        );
    }

    private boolean isChangeable(ConsentType consentType) {
        return consentType == ConsentType.AI_ANALYSIS
                || consentType == ConsentType.COUNSELOR_SHARE;
    }

    private String versionOf(ConsentType consentType) {
        return switch (consentType) {
            case TERMS -> TERMS_VERSION;
            case PRIVACY -> PRIVACY_VERSION;
            case AI_ANALYSIS -> AI_ANALYSIS_VERSION;
            case COUNSELOR_SHARE -> COUNSELOR_SHARE_VERSION;
        };
    }

    private Users requireUser(Long userId) {
        return usersRepository.findById(userId)
                .orElseThrow(this::userNotFound);
    }

    private ResponseStatusException userNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "사용자를 찾을 수 없습니다."
        );
    }
}
