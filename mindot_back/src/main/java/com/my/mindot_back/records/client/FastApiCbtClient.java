// Spring에서 FastAPI CBT 성찰 API를 호출하는 Client
package com.my.mindot_back.records.client;

import com.my.mindot_back.records.dto.ai.FastApiCbtResponseDto;
import com.my.mindot_back.records.dto.ai.FastApiCbtStartRequestDto;
import com.my.mindot_back.records.dto.ai.FastApiCbtTurnRequestDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FastApiCbtClient {

    // yml의 ai.fast-api.base-url을 사용하는 RestClient
    private final RestClient fastApiRestClient;

    public FastApiCbtClient(
            @Qualifier("fastApiRestClient")
            RestClient fastApiRestClient
    ) {
        this.fastApiRestClient = fastApiRestClient;
    }

    /*
     * FastAPI에 성찰 세션의 첫 질문 생성을 요청
     *
     * POST /internal/ai/reflections/start
     */
    public FastApiCbtResponseDto start(
            FastApiCbtStartRequestDto request
    ) {
        try {
            // Spring DTO를 JSON으로 변환해 FastAPI에 POST 요청
            FastApiCbtResponseDto response =
                    fastApiRestClient.post()
                            .uri("/internal/ai/reflections/start")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            // FastAPI 응답 JSON을 DTO로 변환
                            .retrieve()
                            .body(FastApiCbtResponseDto.class);

            // FastAPI가 정상 HTTP 응답을 줬지만 본문이 비어 있는 예외 처리
            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "AI CBT 질문 생성 응답이 비어 있습니다."
                );
            }

            return response;

        } catch (RestClientException e) {
            // FastAPI 서버 중지, 네트워크 실패 -> 502
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI CBT 질문 생성 서버와 통신할 수 없습니다."
            );
        }
    }

    /*
     * FastAPI에 사용자의 답변을 전달하고 다음 CBT 질문 생성을 요청
     *
     * POST /internal/ai/reflections/turn
     */
    public FastApiCbtResponseDto turn(
            FastApiCbtTurnRequestDto request
    ){
        try{
            // 답변이 저장된 전체 CBT 이력을 JSON으로 변환해 FastAPI에 전달
            FastApiCbtResponseDto response =
                    fastApiRestClient.post()
                            .uri("/internal/ai/reflections/turn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            // FastAPI 응답 JSON을 기존 공통 응답 DTO로 변환
                            .retrieve()
                            .body(FastApiCbtResponseDto.class);

            // 정상 HTTP 응답이지만 본문이 없는 비정상 상황 처리
            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "AI CBT 다음 질문 생성 응답이 비어 있습니다."
                );
            }
            return response;
        } catch (RestClientException e) {
            // FastAPI 서버 중지, 네트워크 오류 등의 응답을 502로 변환
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI CBT 다음 질문 생성 서버와 통신할 수 없습니다."
            );
        }
    }
}
