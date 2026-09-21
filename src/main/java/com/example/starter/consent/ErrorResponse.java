package com.example.starter.consent;

/**
 * 统一错误响应体：code 为稳定业务码，message 为可读描述。
 */
public record ErrorResponse(String code, String message) {
}
