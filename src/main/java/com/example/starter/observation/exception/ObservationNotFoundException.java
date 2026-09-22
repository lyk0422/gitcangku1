package com.example.starter.observation.exception;

/**
 * 基线版本或指定历史版本不存在（HTTP 404）。
 */
public class ObservationNotFoundException extends RuntimeException {
    public ObservationNotFoundException(String message) {
        super(message);
    }
}
