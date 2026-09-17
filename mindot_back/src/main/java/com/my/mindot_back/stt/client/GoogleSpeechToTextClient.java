// 음성 데이터를 Google Speech-to-Text v2에 전달하고 전사문을 반환하는 클라이언트
package com.my.mindot_back.stt.client;

import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.speech.v2.AutoDetectDecodingConfig;
import com.google.cloud.speech.v2.RecognizeRequest;
import com.google.cloud.speech.v2.RecognizeResponse;
import com.google.cloud.speech.v2.RecognitionConfig;
import com.google.cloud.speech.v2.RecognitionFeatures;
import com.google.cloud.speech.v2.RecognizerName;
import com.google.cloud.speech.v2.SpeechClient;
import com.google.cloud.speech.v2.SpeechSettings;
import com.google.protobuf.ByteString;
import com.my.mindot_back.common.config.GoogleSttProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class GoogleSpeechToTextClient {

    private final GoogleSttProperties properties;

    public GoogleSpeechToTextClient(GoogleSttProperties properties) {
        this.properties = properties;
    }

    public String transcribe(byte[] audioBytes) throws IOException {
        // WebM과 Ogg 음성의 형식을 Google이 실제 파일 내용으로 판별
        RecognitionConfig config = RecognitionConfig.newBuilder()
                .setAutoDecodingConfig(AutoDetectDecodingConfig.getDefaultInstance())
                .addLanguageCodes(properties.languageCode())
                .setModel(properties.model())
                .setFeatures(RecognitionFeatures.newBuilder()
                        .setEnableAutomaticPunctuation(true)
                        .build())
                .build();

        // '_'는 별도 Recognizer 리소스를 만들지 않는 암시적 Recognizer
        RecognizeRequest request = RecognizeRequest.newBuilder()
                .setRecognizer(RecognizerName.of(
                        properties.projectId(), properties.location(), "_"
                ).toString())
                .setConfig(config)
                .setContent(ByteString.copyFrom(audioBytes))
                .build();

        // Google 요청은 자동 재시도 없이 최대 60초만 대기
        SpeechSettings.Builder settingsBuilder = SpeechSettings.newBuilder();
        RetrySettings retrySettings = settingsBuilder.recognizeSettings()
                .getRetrySettings()
                .toBuilder()
                .setMaxAttempts(1)
                .setInitialRpcTimeoutDuration(Duration.ofSeconds(60))
                .setMaxRpcTimeoutDuration(Duration.ofSeconds(60))
                .setTotalTimeoutDuration(Duration.ofSeconds(60))
                .build();

        settingsBuilder.recognizeSettings().setRetrySettings(retrySettings);

        // 요청이 끝나면 네트워크 클라이언트를 닫고 음성 데이터를 별도 저장하지 않음
        try (SpeechClient client = SpeechClient.create(settingsBuilder.build())) {
            RecognizeResponse response = client.recognize(request);

            return response.getResultsList().stream()
                    .filter(result -> result.getAlternativesCount() > 0)
                    .map(result -> result.getAlternatives(0).getTranscript().trim())
                    .filter(part -> !part.isEmpty())
                    .collect(Collectors.joining(" "));
        }
    }
}