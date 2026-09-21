package com.example.starter.calibration.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应体。
 *
 * @param code     业务错误码
 * @param message  错误描述
 * @param failures 批量放行时各项失败原因；非批量场景为 null 不输出
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<ItemFailure> failures) {

    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }
}
