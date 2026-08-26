// Spring에서 FastAPI 패턴 설명 API를 호출하는 Client
package com.my.mindot_back.records.client;

import com.my.mindot_back.records.dto.ai.FastApiPatternExplanationRequestDto;
import com.my.mindot_back.records.dto.ai.FastApiPatternExplanationResponseDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FastApiPatternExplanationClient {

    // yml의 ai.fast-api.base-url을 사용하는 RestClient
    private final RestClient fastApiRestClient;

    public FastApiPatternExplanationClient(
            @Qualifier("fastApiRestClient")
            RestClient fastApiRestClient
    ) {
        this.fastApiRestClient = fastApiRestClient;
    }

    // FastAPI에 유사 CBT 사례 기반 패턴 설명 생성을 요청
    public FastApiPatternExplanationResponseDto explain(
            FastApiPatternExplanationRequestDto request
    ) {
        try {
            FastApiPatternExplanationResponseDto response =
                    fastApiRestClient.post()
                            .uri("/internal/ai/patterns/explain")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .body(FastApiPatternExplanationResponseDto.class);

            // FastAPI가 성공 응답을 줬지만 본문이 없는 예외 처리
            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "AI 패턴 설명 응답이 비어 있습니다."
                );
            }

            return response;

        } catch (RestClientException e) {
            // FastAPI 서버 중지, 네트워크 오류, 4xx·5xx 응답을 502로 변환
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI 패턴 설명 서버와 통신할 수 없습니다."
            );
        }
    }
}