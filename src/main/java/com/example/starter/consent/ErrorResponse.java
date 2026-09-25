package com.example.starter.consent;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应体：code 为稳定业务码，message 为可读描述，details 为可选结构化明细。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, Object details) {

    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }
}
