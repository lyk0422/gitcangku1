package com.example.starter.api.dto;

/**
 * 统一错误响应体；details 为可区分失败原因的结构化明细，无明细时为 null。
 */
public record ApiError(String error, String message, Object details) {

    public ApiError(String error, String message) {
        this(error, message, null);
    }
}
