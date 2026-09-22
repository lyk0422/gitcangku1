package com.example.starter.api.dto;

/**
 * 统一错误响应体：code 为稳定错误码，message 为人类可读说明。
 */
public record ErrorResponse(String code, String message) {
}
