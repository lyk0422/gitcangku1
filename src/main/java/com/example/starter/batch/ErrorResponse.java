package com.example.starter.batch;

/**
 * 统一错误响应体：code 机器可区分，message 面向人。
 */
public record ErrorResponse(String code, String message) {
}
