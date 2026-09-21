// STT API의 인증·음성 파일 검증·Google 오류 변환을 검증

package com.my.mindot_back.stt.controller;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.my.mindot_back.common.jwt.JwtTokenProvider;
import com.my.mindot_back.stt.client.GoogleSpeechToTextClient;
import com.my.mindot_back.support.PostgresContainerTestBase;
import com.my.mindot_back.users.entity.ConsentEvents;
import com.my.mindot_back.users.entity.ConsentType;
import com.my.mindot_back.users.entity.Users;
import com.my.mindot_back.users.repository.ConsentEventsRepository;
import com.my.mindot_back.users.repository.UsersRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SpeechToTextApiTest
        extends PostgresContainerTestBase {

    private static final long MAX_AUDIO_BYTES =
            9L * 1024 * 1024;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private ConsentEventsRepository consentEventsRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private GoogleSpeechToTextClient googleSpeechToTextClient;

    @Test
    void validWebmReturnsTranscriptAndPassesOriginalBytes()
            throws Exception {
        String accessToken = createUserWithAiConsent(
                "stt-success@example.com"
        );

        byte[] audioBytes = {
                10, 20, 30, 40, 50
        };

        MockMultipartFile audio = new MockMultipartFile(
                "audio",
                "recording.webm",
                "video/webm;codecs=opus",
                audioBytes
        );

        when(
                googleSpeechToTextClient.transcribe(
                        any(byte[].class)
                )
        ).thenReturn("오늘은 기분이 좋다.");

        mockMvc.perform(
                        multipart("/api/stt/transcribe")
                                .file(audio)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.transcript")
                                .value("오늘은 기분이 좋다.")
                );

        var bytesCaptor =
                org.mockito.ArgumentCaptor
                        .forClass(byte[].class);

        verify(googleSpeechToTextClient)
                .transcribe(bytesCaptor.capture());

        assertThat(bytesCaptor.getValue())
                .containsExactly(audioBytes);
        assertThat(bytesCaptor.getValue())
                .isNotEmpty();
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforeGoogleCall()
            throws Exception {
        MockMultipartFile audio = validAudio();

        mockMvc.perform(
                        multipart("/api/stt/transcribe")
                                .file(audio)
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(googleSpeechToTextClient);
    }

    @Test
    void emptyAudioIsRejectedBeforeGoogleCall()
            throws Exception {
        String accessToken = createUserWithAiConsent(
                "stt-empty@example.com"
        );

        MockMultipartFile audio = new MockMultipartFile(
                "audio",
                "empty.webm",
                "audio/webm",
                new byte[0]
        );

        mockMvc.perform(
                        multipart("/api/stt/transcribe")
                                .file(audio)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.message")
                                .value("음성 파일이 비어 있습니다.")
                );

        verifyNoInteractions(googleSpeechToTextClient);
    }

    @Test
    void oversizedAudioIsRejectedBeforeGoogleCall()
            throws Exception {
        String accessToken = createUserWithAiConsent(
                "stt-oversized@example.com"
        );

        MockMultipartFile audio = new MockMultipartFile(
                "audio",
                "large.webm",
                "audio/webm",
                new byte[(int) MAX_AUDIO_BYTES + 1]
        );

        mockMvc.perform(
                        multipart("/api/stt/transcribe")
                                .file(audio)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(
                        jsonPath("$.message")
                                .value("음성 파일은 9MB 이하여야 합니다.")
                );

        verifyNoInteractions(googleSpeechToTextClient);
    }

    @Test
    void unsupportedMimeTypeIsRejectedBeforeGoogleCall()
            throws Exception {
        String accessToken = createUserWithAiConsent(
                "stt-unsupported@example.com"
        );

        MockMultipartFile audio = new MockMultipartFile(
                "audio",
                "recording.mp4",
                "video/mp4",
                new byte[]{1, 2, 3}
        );

        mockMvc.perform(
                        multipart("/api/stt/transcribe")
                                .file(audio)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(
                        jsonPath("$.message")
                                .value("지원하지 않는 음성 형식입니다.")
                );

        verifyNoInteractions(googleSpeechToTextClient);
    }

    @Test
    void blankRecognitionResultReturnsUnprocessableEntity()
            throws Exception {
        String accessToken = createUserWithAiConsent(
                "stt-blank-result@example.com"
        );

        when(
                googleSpeechToTextClient.transcribe(
                        any(byte[].class)
                )
        ).thenReturn("   ");

        mockMvc.perform(
                        multipart("/api/stt/transcribe")
                                .file(validAudio())
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "음성에서 텍스트를 인식하지 못했습니다"
                                )
                );
    }

    @Test
    void googleConnectionFailureReturnsBadGateway()
            throws Exception {
        String accessToken = createUserWithAiConsent(
                "stt-connection-error@example.com"
        );

        when(
                googleSpeechToTextClient.transcribe(
                        any(byte[].class)
                )
        ).thenThrow(
                new IOException("private connection detail")
        );

        mockMvc.perform(
                        multipart("/api/stt/transcribe")
                                .file(validAudio())
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "음성 변환 서비스에 연결할 수 없습니다"
                                )
                )
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        org.hamcrest.Matchers
                                                .not(
                                                        org.hamcrest.Matchers
                                                                .containsString(
                                                                        "private connection detail"
                                                                )
                                                )
                                )
                );
    }

    @ParameterizedTest
    @MethodSource("googleApiFailures")
    void googleApiFailuresAreMappedToPublicStatuses(
            StatusCode.Code googleCode,
            int expectedStatus,
            String expectedMessage
    ) throws Exception {
        String accessToken = createUserWithAiConsent(
                "stt-google-"
                        + googleCode.name().toLowerCase()
                        + "@example.com"
        );

        ApiException apiException =
                mock(ApiException.class);
        StatusCode statusCode =
                mock(StatusCode.class);

        when(apiException.getStatusCode())
                .thenReturn(statusCode);
        when(statusCode.getCode())
                .thenReturn(googleCode);

        when(
                googleSpeechToTextClient.transcribe(
                        any(byte[].class)
                )
        ).thenThrow(apiException);

        mockMvc.perform(
                        multipart("/api/stt/transcribe")
                                .file(validAudio())
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(accessToken)
                                )
                )
                .andExpect(status().is(expectedStatus))
                .andExpect(
                        jsonPath("$.status")
                                .value(expectedStatus)
                )
                .andExpect(
                        jsonPath("$.message")
                                .value(expectedMessage)
                );
    }

    private static Stream<Arguments> googleApiFailures() {
        return Stream.of(
                Arguments.of(
                        StatusCode.Code.INVALID_ARGUMENT,
                        422,
                        "음성 형식을 분석할 수 없습니다"
                ),
                Arguments.of(
                        StatusCode.Code.DEADLINE_EXCEEDED,
                        504,
                        "음성 변환 시간이 초과됐습니다"
                ),
                Arguments.of(
                        StatusCode.Code.RESOURCE_EXHAUSTED,
                        503,
                        "음성 변환 서비스의 사용량 한도에 도달했습니다"
                ),
                Arguments.of(
                        StatusCode.Code.INTERNAL,
                        502,
                        "음성 변환 서비스에 오류가 발생했습니다"
                )
        );
    }

    private String createUserWithAiConsent(String email) {
        Users user = usersRepository.saveAndFlush(
                Users.create(
                        email,
                        "unused-password-hash",
                        "STT 테스트"
                )
        );

        consentEventsRepository.saveAndFlush(
                ConsentEvents.grant(
                        user,
                        ConsentType.AI_ANALYSIS,
                        "ai-analysis-v1"
                )
        );

        return jwtTokenProvider.createAccessToken(user.getId());
    }

    private MockMultipartFile validAudio() {
        return new MockMultipartFile(
                "audio",
                "recording.webm",
                "audio/webm",
                new byte[]{1, 2, 3, 4}
        );
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}