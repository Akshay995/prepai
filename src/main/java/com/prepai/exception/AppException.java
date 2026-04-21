package com.prepai.exception;

import lombok.Getter;

@Getter
public class AppException extends RuntimeException {
    private final int status;

    public AppException(String message, int status) {
        super(message);
        this.status = status;
    }
}
