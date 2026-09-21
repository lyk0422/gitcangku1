package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 批准请求体；批准人与角色通过 X-Actor-Id / X-Approval-Role 请求头提供。
 */
public record ApproveRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
