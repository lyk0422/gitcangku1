package com.example.starter.race.service;

/**
 * 业务规则不满足、请求无法处理（HTTP 422）。
 */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
