// 사용자가 현재 CBT 질문에 답한 내용을 서버로 전달하는 요청 DTO
package com.my.mindot_back.records.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 프론트는 답변 내용만 보냄
// sessionId와 현재 questionCode는 URL, DB의 성찰 세션에서 서버가 직접 확인
public record ReflectionSessionTurnRequestDto (

        // 공백만 있는 답변은 허용X
        @NotBlank

        // 답변 길이 최대 4000자 제한
        @Size(max = 4000)
        String answer
){
}
