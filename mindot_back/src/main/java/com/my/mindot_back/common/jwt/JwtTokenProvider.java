// Access Token을 생성하고 검증하는 공통 JWT 클래스
package com.my.mindot_back.common.jwt;

import com.my.mindot_back.common.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {
    // yml의 auth.jwt 값을 담은 설정 객체
    private final JwtProperties jwtProperties;

    // 로그인한 사용자의 Access Token 발급
    public String createAccessToken(Long userId) {
        // 토큰을 만든 시각 (UTC)
        Instant now = Instant.now();

        // 토큰 만료 시각 → yml의 access-token-expiration 설정 시간 후
        Instant expiration = now.plus(jwtProperties.accessTokenExpiration());

        // jwt 내부 구성
        /*
        *subject : 토큰의 주인 -> 사용자 id를 문자열로 저장
        * issuedAt : 토큰 발급 시각
        * expiration : 토큰 만료 시각 -> 15분 후
        *  signWith: 비밀키로 서명해서 위조 막음
        *
         */
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(getSigningKey())
                .compact();
    }

    // 전달받은 jwt가 정상인지 확인
    public boolean validateToken(String token) {
        try {
            // 검증 규칙 설정
            Jwts.parser()
                    // 토큰의 서명이 서버 비밀키와 일치하는지 검사하도록 설정
                    .verifyWith(getSigningKey())
                    // 설정 끝, 실제 JWT 검증기 생성
                    .build()
                    // 서명, 형식, 만료 시간 모두 검사 (실제 검사 하는 부분)
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // 위조·만료·형식 오류면 false를 반환
            // 이후 Security Filter가 로그인되지 않은 요청으로 처리
            return false;
        }
    }

    // jwt의 subject에 저장한 사용자 ID 꺼냄
    public Long getUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                // jwt의 가운데(데이터) 가져옴
                .getPayload();

        //  createAccessToken() 에서 문자열로 저장한 subject를 Long으로 복원
        return Long.valueOf(claims.getSubject());
    }

    // 환경변수 JWT_SECRET의 Base64 문자열을
    // HMAC 서명, 검증에 사용할 SecretKey로 변환

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtProperties.secret());

        return Keys.hmacShaKeyFor(keyBytes);
    }
}
