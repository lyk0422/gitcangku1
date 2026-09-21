package com.example.starter.incident;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(int status, String message) {
}
