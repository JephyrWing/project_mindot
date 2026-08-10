package com.my.mindot_back.common.exception;

public class RefreshTokenReuseException
        extends RuntimeException {

    public RefreshTokenReuseException() {
        super("이미 사용된 Refresh Token이 감지되었습니다.");
    }
}