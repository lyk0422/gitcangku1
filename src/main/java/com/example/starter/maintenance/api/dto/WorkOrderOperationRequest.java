package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 工单状态变更请求（开始/关闭/取消/终止共用）。
 *
 * @param expectedWorkOrderVersion 工单期望版本号，与当前工单版本不一致时返回 409
 */
public record WorkOrderOperationRequest(
        @NotNull Long expectedWorkOrderVersion) {
}
