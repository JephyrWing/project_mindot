// 모든 HTTP 요청에서 Authorization 헤더의 JWT 를 확인하는 필터
package com.my.mindot_back.common.jwt;


/*
*요청 흐름:
* 클라이언트 요청
* -> JwtAuthenticationFilter
* -> JWT 검증
* -> 정상 토큰이면 SecurityContext에 로그인 사용자 ID 등록
* -> Controller
 */

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
// 상속 이유: 같은 요청에서 JWT 인증 로직이 중복 실행되지 않도록 함

    // JWT 생성, 검증, 사용자 ID 추출을 담당하는 공통 클래스
    private final JwtTokenProvider jwtTokenProvider;

    // 요청마다 한번만 실행되는 필터 메서드

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

                // Spring Security가 알아볼 수 있는 인증 완료 객체 만듦
                /*
                *principal : 현재 로그인 사용자 식별값 -> userId
                * credentials : 비밀번호 다시 보관할 필요 X -> null
                * authorities : user, admin 같은 역할 컬럼 X -> 빈 목록
                 */
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userId,
                                null,
//                                List.of()
                                 List.of(new SimpleGrantedAuthority("ROLE_USER"))
                        );

                // 현재 요청의 SecurityContext에 인증 정보 저장 -> controller에서 현재 로그인한 id 신뢰 가능
                SecurityContextHolder.getContext()
                        .setAuthentication(authentication);
            }
        }
        // JWT 가 없거나 유효하지 않아도 다음 필터로 요청 넘김
        // SecurityConfig의 authenticated()가 보호 API 접근 가능 여부 최종 판단
        filterChain.doFilter(request, response);
    }
}
