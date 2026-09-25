package com.example.starter.api.dto;

/**
 * 统一错误响应体；details 用于 422 等场景携带结构化命中路径，无明细时为 null。
 */
public record ApiError(String error, String message, Object details) {
}
