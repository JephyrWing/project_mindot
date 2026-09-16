package com.my.mindot_back.users.service;

import com.my.mindot_back.users.dto.UsersSignupRequestDto;
import com.my.mindot_back.users.dto.UsersSignupResponseDto;
import com.my.mindot_back.users.dto.UsersLoginRequestDto;
import com.my.mindot_back.users.dto.UsersLoginResponseDto;
import com.my.mindot_back.users.entity.AccountStatus;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.my.mindot_back.users.dto.UsersProfileResponseDto;
import com.my.mindot_back.users.dto.UsersProfileUpdateRequestDto;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UsersService {
    // users 테이블 저장·조회 Repository
    private final UsersRepository usersRepository;

    // 일반·소셜 회원가입이 함께 사용하는 필수 동의 이력 저장 Service
    private final ConsentEventsService consentEventsService;

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

        // 필수 동의 TERMS·PRIVACY·AI_ANALYSIS 이력 저장
        consentEventsService.grantRequiredConsents(savedUser);

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

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(
                dto.password(),
                user.getPasswordHash()
        )) {
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
                accessToken,
                user.getUserRole().name()
        );
    }

    // 로그인 사용자 프로필 조회
    @Transactional
    public UsersProfileResponseDto getProfile(Long userId) {
        // JWT에서 추출한 사용자 ID로 현재 활성 회원 조회
        Users user = findActiveUser(userId);

        // Entity 내부 인증 정보를 제외한 프로필 정보만 반환
        return UsersProfileResponseDto.from(user);
    }

    // 로그인 사용자 프로필 수정
    @Transactional
    public UsersProfileResponseDto updateProfile(
            Long userId,
            UsersProfileUpdateRequestDto dto
    ) {
        // 다른 사용자의 ID를 요청값으로 받지 않고 JWT 사용자만 수정
        Users user = findActiveUser(userId);

        // 닉네임 앞뒤 공백 제거 후 Entity 변경
        // 트랜잭션 종료 시 JPA 변경 감지로 UPDATE 실행
        user.updateDisplayName(dto.displayName().trim());

        // 수정된 프로필 정보 반환
        return UsersProfileResponseDto.from(user);
    }

    // 프로필 기능에서 공통으로 사용하는 활성 회원 조회
    private Users findActiveUser(Long userId) {
        // JWT에는 사용자가 존재했지만 이후 삭제된 경우 404 처리
        Users user = usersRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "회원 정보를 찾을 수 없습니다."
                ));

        // 정지 또는 탈퇴 계정의 프로필 접근 차단
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "사용할 수 없는 계정입니다."
            );
        }

        return user;
    }

}
