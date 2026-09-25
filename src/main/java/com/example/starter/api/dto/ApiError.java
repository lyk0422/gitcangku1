package com.example.starter.api.dto;

/**
 * 统一错误响应体；details 携带可选的结构化明细（如发布被阻断时的全部命中制品），无明细时为 null。
 */
public record ApiError(String error, String message, Object details) {

    public ApiError(String error, String message) {
        this(error, message, null);
    }
}
