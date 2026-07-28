package com.tuapp.eventfoto.common.exception;

public class MaxUploadLimitReachedException extends RuntimeException {

    public MaxUploadLimitReachedException(String message) {
        super(message);
    }
}
