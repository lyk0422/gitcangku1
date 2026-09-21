package com.example.starter.web;

/**
 * 统一错误响应体。
 *
 * @param code    稳定业务码，如 BAD_REQUEST / GRANT_NOT_FOUND / RECORD_NOT_FOUND
 *                / PAYLOAD_CONFLICT / IDEMPOTENCY_CONFLICT / ALREADY_REVOKED / CONSENT_REVOKED
 * @param message 错误描述
 */
public record ErrorResponse(String code, String message) {
}
