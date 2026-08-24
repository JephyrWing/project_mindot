package com.my.mindot_back.common.exception;

public class InvalidRefreshTokenException
        extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("유효하지 않은 Refresh Token입니다.");
    }
}
