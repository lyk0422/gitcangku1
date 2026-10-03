package com.example.starter.dive.service;

/**
 * 请求参数非法，映射HTTP 400。
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}