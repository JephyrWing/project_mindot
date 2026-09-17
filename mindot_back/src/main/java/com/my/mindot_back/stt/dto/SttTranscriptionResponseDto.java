// 음성 인식 결과를 프론트에 반환하는 응답 DTO
package com.my.mindot_back.stt.dto;

public record SttTranscriptionResponseDto(
        String transcript
) {
}