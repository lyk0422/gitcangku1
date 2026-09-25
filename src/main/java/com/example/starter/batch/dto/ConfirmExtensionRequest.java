package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 延期确认请求。确认人由 X-Actor-Id 给出，须为不同于复检人的批准角色（X-Approval-Role）。
 */
public record ConfirmExtensionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
