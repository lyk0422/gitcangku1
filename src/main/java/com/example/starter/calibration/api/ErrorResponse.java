package com.example.starter.calibration.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应体。
 *
 * @param code     业务错误码
 * @param message  错误描述
 * @param failures 批量放行 409 时各项失败原因；其他场景为 null 不输出
 * @param details  放行环境 422 时的实际值与命中版本现场；其他场景为 null 不输出
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<ItemFailure> failures, Object details) {

    public ErrorResponse(String code, String message) {
        this(code, message, null, null);
    }

    public ErrorResponse(String code, String message, List<ItemFailure> failures) {
        this(code, message, failures, null);
    }

    public ErrorResponse(String code, String message, Object details) {
        this(code, message, null, details);
    }
}
