package com.example.starter.api.dto;

/**
 * 统一错误响应体。
 *
 * @param details 结构化诊断明细（如锁定闭包内已生效弃用项列表）；无明细时为 null
 */
public record ApiError(String error, String message, Object details) {

    public ApiError(String error, String message) {
        this(error, message, null);
    }
}
