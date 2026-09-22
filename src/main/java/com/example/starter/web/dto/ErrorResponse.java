package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param status    HTTP 状态码
 * @param error     错误类型
 * @param message   错误说明
 * @param timestamp 错误发生时间（UTC）
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        Instant timestamp
) {
}
