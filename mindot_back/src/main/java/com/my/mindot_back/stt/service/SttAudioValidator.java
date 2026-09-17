// STT 요청으로 받은 음성 파일의 크기와 형식을 검사하는 컴포넌트
package com.my.mindot_back.stt.service;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SttAudioValidator {

    // Spring multipart의 max-file-size: 9MB와 동일한 한도
    private static final long MAX_AUDIO_BYTES = 9L * 1024 * 1024;

    // WebM과 Ogg 및 WAV 형식을 허용
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "audio/webm",
            "video/webm",
            "audio/ogg",
            "audio/wav",
            "audio/wave",
            "audio/x-wav"
    );

    public void validate(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "음성 파일이 비어 있습니다."
            );
        }

        if (audio.getSize() > MAX_AUDIO_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "음성 파일은 9MB 이하여야 합니다."
            );
        }

        // MIME 파라미터(예: ;codecs=opus)를 제외하고 기본 형식만 비교
        try {
            MediaType mediaType = MediaType.parseMediaType(audio.getContentType());
            String baseType = mediaType.getType() + "/" + mediaType.getSubtype();

            if (!ALLOWED_TYPES.contains(baseType)) {
                throw new ResponseStatusException(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 음성 형식입니다."
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 음성 형식입니다."
            );
        }
    }
}