package com.example.starter.batch.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 清洗确认请求，通过路径中的 cleaningId 明确引用清洗记录。
 * 确认人由 X-Actor-Id 提供，须为 QUALITY 角色（X-Approval-Role）且不同于提交人；
 * expectedVersion 为期望的清洗记录版本（待确认为 0）。
 */
public record ConfirmCleaningRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "expectedVersion 不能为空")
        @Min(value = 0, message = "expectedVersion 不能为负") Long expectedVersion
) {
}