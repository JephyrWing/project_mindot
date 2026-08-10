package com.my.mindot_back.users.service;

import com.my.mindot_back.users.dto.UsersSignupRequestDto;
import com.my.mindot_back.users.dto.UsersSignupResponseDto;
import com.my.mindot_back.users.entity.ConsentEvents;
import com.my.mindot_back.users.entity.ConsentType;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.ConsentEventsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UsersService {
    private static final String TERMS_VERSION = "terms-v1";
    private static final String PRIVACY_VERSION = "privacy-v1";
    private static final String AI_ANALYSIS_VERSION = "ai-analysis-v1";

    // users 테이블 저장·조회 Repository
    private final UsersRepository usersRepository;

    // consent_events 테이블 저장 Repository
    private final ConsentEventsRepository consentEventsRepository;

    private final PasswordEncoder passwordEncoder;

    // 회원가입 처리 메서드
    /*
    *users에 사용자 저장, 필수 동의 3개 저장
    * 하나라도 실패하면 DB에 저장되지 않고 rollback
    */
    @Transactional
    public UsersSignupResponseDto signup(UsersSignupRequestDto dto) {
        String email = dto.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        // 이메일 중복 확인
        if (usersRepository.existsByEmail(email)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "이미 사용 중인 이메일입니다."
            );
        }

        // 원문 비밀번호를 BCrypt 해시로 변환
        String encodedPassword = passwordEncoder.encode(dto.password());

        // 회원가입 용 Users Entity 생성
        Users user = Users.create(
                email,
                encodedPassword,
                dto.displayName().trim()
        );

        // users 테이블에 저장
        // 저장 후 savedUser에 DB가 생성한 id, createdAt 값 포함됨
        Users savedUser = usersRepository.save(user);

        // 필수 동의 3가지 이벤트로 만듦
        // 전제: DTO의 @AssertTrue
        List<ConsentEvents> consentEvents = List.of(
                ConsentEvents.grant(
                        savedUser,
                        ConsentType.TERMS,
                        TERMS_VERSION
                ),
                ConsentEvents.grant(
                        savedUser,
                        ConsentType.PRIVACY,
                        PRIVACY_VERSION
                ),
                ConsentEvents.grant(
                        savedUser,
                        ConsentType.AI_ANALYSIS,
                        AI_ANALYSIS_VERSION
                )
        );

        // consent_events 테이블에 동의 이력 3건 저장
        consentEventsRepository.saveAll(consentEvents);

        // Response DTO 반환 (passwordHash 포함 X)
        return UsersSignupResponseDto.from(savedUser);

    }

}
