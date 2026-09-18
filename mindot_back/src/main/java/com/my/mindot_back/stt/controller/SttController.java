// 로그인 사용자의 음성 파일을 받아 STT 결과를 반환하는 컨트롤러
package com.my.mindot_back.stt.controller;

import com.my.mindot_back.stt.dto.SttTranscriptionResponseDto;
import com.my.mindot_back.stt.service.SpeechToTextService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/stt")
public class SttController {

    private final SpeechToTextService speechToTextService;

    public SttController(SpeechToTextService speechToTextService) {
        this.speechToTextService = speechToTextService;
    }

    // 로그인 사용자 ID와 업로드한 음성을 서비스에 전달
    @PostMapping(
            value = "/transcribe",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public SttTranscriptionResponseDto transcribe(
            @AuthenticationPrincipal Long userId,
            @RequestPart("audio") MultipartFile audio
    ) {
        String transcript = speechToTextService.transcribe(userId, audio);
        return new SttTranscriptionResponseDto(transcript);
    }
}