package com.example.starter.web.dto;

/**
 * 错误响应体。
 */
public record ErrorResponse(
        String code,
        String message
) {
}
