package com.my.mindot_back.common.exception;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
// 오류처리 결과를 정리해서 보낼 DTO
public class ErrorResponse {
    // 에러상태코드
    private int status;
    // 오류 메시지
    private String message;
}
