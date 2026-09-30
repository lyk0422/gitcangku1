package com.example.starter.cue.exception;

/**
 * 资源不存在，映射 HTTP 404。
 */
public class CueNotFoundException extends RuntimeException {

    public CueNotFoundException(String message) {
        super(message);
    }
}
