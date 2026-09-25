package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 复检延期确认请求。确认人通过 X-Actor-Id / X-Approval-Role 请求头提供，
 * 必须是不同于复检人的批准角色；确认后延期才在同一事务内生效。
 */
public record ConfirmExtensionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
