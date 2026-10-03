package com.example.starter.web.dto;

/**
 * 统一错误响应体：机器可读原因码与消息。
 */
public record ErrorResponse(String reason, String message) {
}