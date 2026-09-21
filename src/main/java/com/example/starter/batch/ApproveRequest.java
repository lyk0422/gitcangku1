package com.example.starter.batch;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 放行批准请求。批准人与角色通过 X-Actor-Id、X-Approval-Role 请求头提供。
 *
 * @param commandKey 命令幂等键
 */
public record ApproveRequest(
        @NotBlank @Size(max = 64) String commandKey) {
}
