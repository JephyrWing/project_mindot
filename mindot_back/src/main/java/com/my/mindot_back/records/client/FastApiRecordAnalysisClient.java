// Spring에서 FastAPI 감정 원문 구조화 API를 호출하는 Client
package com.my.mindot_back.records.client;

import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisRequestDto;
import com.my.mindot_back.records.dto.ai.FastApiRecordAnalysisResponseDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FastApiRecordAnalysisClient {
    // FastApiClientConfig에서 만든 FastAPI 전용 RestClient
    private RestClient fastApiRestClient;

    public FastApiRecordAnalysisClient(
            // 여러 RestClient Bean 중 FastAPI용 Bean을 정확히 선택
            @Qualifier("fastApiRestClient") RestClient fastApiRestClient
    ){
        this.fastApiRestClient = fastApiRestClient;
    }

    // FastAPI에 감정 원문을 보내고 구조화 결과 반환받음
    //  POST /internal/ai/records
    /*
     * 요청:  { "rawText": "..." }
     * 응답:  record, risk, meta
     */
    public FastApiRecordAnalysisResponseDto analyze(String rawText) {
        try {
            FastApiRecordAnalysisResponseDto response =
                    fastApiRestClient.post()
                            // baseUrl 뒤에 붙는 API 경로
                            .uri("/internal/ai/records")
                            // 요청 본문이 JSON임을 FastAPI에 알림
                            .contentType(MediaType.APPLICATION_JSON)
                            // Java record를 JSON 요청 본문으로 변환 (보낼 값)
                            .body(new FastApiRecordAnalysisRequestDto(rawText))
                            // FastAPI의 HTTP 응답을 받음
                            .retrieve()
                            // 응답 JSON을 FastApiRecordAnalysisResponseDto로 변환 (받을 값)
                            .body(FastApiRecordAnalysisResponseDto.class);

            // HTTP 200이어도 응답 본문이 비어있으면 502 오류 반환
            // 502: spring 정상, FastAPI 서버 문제
            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "AI 분석 결과가 비어 있습니다."
                );
            }

            return response;
        } catch (RestClientException e){
            // FastAPI 서비 미실행, 네트워크 오류, 4xx, 5xx 응답을 502 오류 반환
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI 분석 서버와 통신할 수 없습니다."
            );
        }
    }
}
