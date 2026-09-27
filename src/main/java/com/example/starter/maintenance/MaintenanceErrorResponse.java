package com.example.starter.maintenance;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 观测站维护业务统一错误响应体。
 *
 * @param status    HTTP 状态码
 * @param error     状态短语
 * @param errorCode 可区分的业务错误码（如 WINDOW_OVERLAP / WINDOW_SEALED / BATCH_REJECTED）
 * @param message   错误描述（数量或时长场景包含实际值、要求值或差额）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MaintenanceErrorResponse(
        int status,
        String error,
        String errorCode,
        String message) {
}
