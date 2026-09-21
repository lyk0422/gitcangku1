package com.example.starter.evidence.web;

/**
 * 统一错误响应体。
 *
 * @param code    稳定错误码（BAD_REQUEST/NOT_FOUND/CONFLICT/SEAL_BROKEN）
 * @param message 人类可读描述
 */
public record ErrorResponse(String code, String message) {
}
