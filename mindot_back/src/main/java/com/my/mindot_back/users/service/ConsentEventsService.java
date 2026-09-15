// 일반·소셜 회원가입의 필수 동의 이력 3건을 동일한 버전으로 저장하는 Service
package com.my.mindot_back.users.service;

import com.my.mindot_back.users.entity.ConsentEvents;
import com.my.mindot_back.users.entity.ConsentType;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.ConsentEventsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConsentEventsService {

    private static final String TERMS_VERSION = "terms-v1";
    private static final String PRIVACY_VERSION = "privacy-v1";
    private static final String AI_ANALYSIS_VERSION = "ai-analysis-v1";

    private final ConsentEventsRepository consentEventsRepository;

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
}