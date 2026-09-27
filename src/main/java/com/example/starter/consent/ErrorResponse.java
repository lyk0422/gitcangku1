package com.example.starter.consent;

import java.util.List;

/**
 * 统一错误响应体：code 为稳定业务码，message 为可读描述；
 * details 为批量操作的逐条失败明细（无明细时为 null，保持单对象错误历史结构不变）。
 */
public record ErrorResponse(String code, String message, List<FailureDetail> details) {

    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }

    /**
     * 单条失败明细。
     *
     * @param target  失败对象标识（如记录键），无对象上下文时为 null
     * @param code    该对象的稳定失败码
     * @param message 可读描述
     */
    public record FailureDetail(String target, String code, String message) {
    }
}
