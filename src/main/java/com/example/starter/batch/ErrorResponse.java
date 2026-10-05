package com.example.starter.batch;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应体：code 机器可区分，message 面向人；
 * details 为可选结构化明细（如配方各成分差额），为 null 时不序列化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, Object details) {

    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }
}