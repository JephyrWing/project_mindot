package com.my.mindot_back.users.service;

import com.my.mindot_back.users.dto.UsersSignupRequestDto;
import com.my.mindot_back.users.dto.UsersSignupResponseDto;
import com.my.mindot_back.users.dto.UsersLoginRequestDto;
import com.my.mindot_back.users.dto.UsersLoginResponseDto;
import com.my.mindot_back.users.entity.AccountStatus;
import com.my.mindot_back.users.entity.ConsentEvents;
import com.my.mindot_back.users.entity.ConsentType;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.ConsentEventsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
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

    // 로그인 성공한 사용자에게 Access Token을 발급하는 공통 JWT 클래스
    private final JwtTokenProvider jwtTokenProvider;

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

    // 로그인 처리 메서드
    /*
    *1. 이메일로 사용자 조회
    * 2. 암호화된 DB 저장 비밀번호와 원문 비밀번호 비교
    * 3. 정상 사용자라면 정보를 Response DTO로 반환
     */
    public UsersLoginResponseDto login(UsersLoginRequestDto dto) {
        String email = dto.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        Users user = usersRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "이메일 또는 비밀번호가 올바르지 않습니다."
                ));

        if (!passwordEncoder.matches(dto.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "이메일 또는 비밀번호가 올바르지 않습니다."
            );
        }

        // ACTIVE가 아니면 로그인 불가
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인할 수 없는 계정입니다."
            );
        }

        // 비밀번호와 계정 상태 검증을 모두 통과한 사용자에게 15분짜리 Access Token 발급
        String accessToken = jwtTokenProvider.createAccessToken(user.getId());

        return new UsersLoginResponseDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                accessToken
        );
    }

}
