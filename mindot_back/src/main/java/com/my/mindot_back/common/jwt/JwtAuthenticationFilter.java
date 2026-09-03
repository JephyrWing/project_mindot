// 모든 HTTP 요청에서 JWT와 현재 사용자 계정 상태·권한을 확인하는 필터
package com.my.mindot_back.common.jwt;

import com.my.mindot_back.users.entity.AccountStatus;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.UsersRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import org.springframework.http.HttpHeaders;
import java.util.List;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // JWT 생성, 검증, 사용자 ID 추출을 담당하는 공통 클래스
    private final JwtTokenProvider jwtTokenProvider;

    // JWT 사용자 ID로 현재 계정 상태와 실제 역할을 조회
    private final UsersRepository usersRepository;

    // Authorization Bearer Token을 검증하고 SecurityContext에 인증 정보를 저장
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 헤더가 있고 Bearer 공백으로 시작하는 경우에만 JWT 꺼냄
        // "Bearer " : 7글자 -> substring(7)부터 실제 토큰
        if(authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            String accessToken = authorizationHeader.substring(7);

            // 서명, 형식, 만료시간 검증을 모두 통과한 토큰만 인증 처리
            if (jwtTokenProvider.validateToken(accessToken)) {
                // JWT subject 에 저장해둔 사용자 DB ID 를 가져옴
                Long userId = jwtTokenProvider.getUserId(accessToken);

                // 탈퇴·정지·삭제된 계정은 기존 JWT가 남아 있어도 인증하지 않음
                usersRepository.findById(userId)
                        .filter(user ->
                                user.getStatus() == AccountStatus.ACTIVE
                        )
                        .ifPresent(user -> authenticate(user));
            }
        }

        // 인증 실패 또는 토큰이 없는 요청은 다음 필터로 넘기며,
        // 보호 API는 SecurityConfig에서 401로 차단
        filterChain.doFilter(request, response);
    }

    // DB에 저장된 사용자 역할을 Spring Security 권한으로 등록
    private void authenticate(Users user) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        user.getId(),
                        null,
                        List.of(
                                new SimpleGrantedAuthority(
                                        user.getUserRole().name()
                                )
                        )
                );

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);
    }
}