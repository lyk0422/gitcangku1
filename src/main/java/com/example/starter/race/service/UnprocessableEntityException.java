package com.example.starter.race.service;

/**
 * 业务规则校验失败（语义上无法处理），映射为 HTTP 422。
 */
public class UnprocessableEntityException extends RuntimeException {

    public UnprocessableEntityException(String message) {
        super(message);
    }
}
