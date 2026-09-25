package com.example.starter.api.dto;

import java.util.List;

/**
 * 统一错误响应体。
 *
 * @param violations 来源策略违规明细（可区分原因 + 完整路径）；无明细时为空列表
 */
public record ApiError(String error, String message, List<ViolationView> violations) {

    public ApiError(String error, String message) {
        this(error, message, List.of());
    }
}
