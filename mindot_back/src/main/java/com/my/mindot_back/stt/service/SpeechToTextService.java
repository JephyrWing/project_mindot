// 음성 파일을 검증하고 Google STT 결과를 서비스 응답으로 변환하는 서비스
package com.my.mindot_back.stt.service;

import com.google.api.gax.rpc.ApiException;
import com.my.mindot_back.stt.client.GoogleSpeechToTextClient;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.my.mindot_back.users.service.ConsentEventsService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SpeechToTextService {

    private final SttAudioValidator audioValidator;
    private final GoogleSpeechToTextClient googleClient;
    private final ConsentEventsService consentEventsService;

    public SpeechToTextService(
            SttAudioValidator audioValidator,
            GoogleSpeechToTextClient googleClient,
            ConsentEventsService consentEventsService
    ) {
        this.audioValidator = audioValidator;
        this.googleClient = googleClient;
        this.consentEventsService = consentEventsService;
    }

    public String transcribe(Long userId, MultipartFile audio) {
        // 외부 음성 인식 서비스에 전달하기 전에 AI 분석 동의 상태 확인
        consentEventsService.requireAiAnalysisConsent(userId);
        audioValidator.validate(audio);

        byte[] audioBytes;
        try {
            audioBytes = audio.getBytes();
        } catch (IOException exception) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "음성 파일을 읽을 수 없습니다"
            );
        }



        try {
            String transcript = googleClient.transcribe(audioBytes);

            if (transcript.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "음성에서 텍스트를 인식하지 못했습니다"
                );
            }

            return transcript;
        } catch (ApiException exception) {
            log.warn("Google STT 실패 code={}", exception.getStatusCode().getCode());

            // Google 오류 상세에는 민감 정보가 있을 수 있어 응답에 포함하지 않음
            switch (exception.getStatusCode().getCode()) {
                case INVALID_ARGUMENT -> throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "음성 형식을 분석할 수 없습니다"
                );
                case DEADLINE_EXCEEDED -> throw new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "음성 변환 시간이 초과됐습니다"
                );
                case RESOURCE_EXHAUSTED -> throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "음성 변환 서비스의 사용량 한도에 도달했습니다"
                );
                default -> throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "음성 변환 서비스에 오류가 발생했습니다"
                );
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "음성 변환 서비스에 연결할 수 없습니다"
            );
        }
    }
}