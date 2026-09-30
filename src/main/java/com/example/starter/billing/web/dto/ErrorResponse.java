package com.example.starter.billing.web.dto;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param timestamp 错误发生时间（UTC）
 * @param status    HTTP 状态码
 * @param code      稳定错误码
 * @param message   错误说明
 * @param path      请求路径
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path) {
}
