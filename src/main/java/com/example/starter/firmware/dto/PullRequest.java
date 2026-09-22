package com.example.starter.firmware.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 设备拉取投放任务请求。
 *
 * @param requestId 全局唯一请求号，用于幂等去重
 * @param deviceId  设备唯一标识
 */
public record PullRequest(
        @NotBlank String requestId,
        @NotBlank String deviceId) {
}
