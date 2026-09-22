package com.example.starter.race.service;

/**
 * 请求参数不满足业务约束，映射 HTTP 400。
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
