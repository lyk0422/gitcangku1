package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 清洗确认请求。确认人由 X-Actor-Id 请求头提供，须为 QUALITY 角色且不同于提交人；
 * expectedVersion 必须等于清洗记录当前版本，否则返回 409。
 */
public record CleaningConfirmRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion
) {
}