package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 复检延期确认请求体。确认人（批准角色）通过 X-Actor-Id 请求头提供。
 */
public record ExtensionConfirmRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
