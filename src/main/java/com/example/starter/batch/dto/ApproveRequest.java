package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 批准请求体；批准人与角色通过 X-Actor-Id / X-Approval-Role 请求头提供。
 * 绑定耦合公差规范的批次必须提供 expectedVersion 且等于当前绑定版本，否则 400/409；
 * 未绑定规范的批次不得提供 expectedVersion。
 */
public record ApproveRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        Integer expectedVersion
) {
}
