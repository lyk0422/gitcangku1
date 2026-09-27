package com.example.starter.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应体。
 *
 * @param details 可选的结构化明细（如锁定 422 的已生效弃用条目列表）；null 时不出现在响应中
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, Object details) {

    public ApiError(String error, String message) {
        this(error, message, null);
    }
}
