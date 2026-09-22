package com.example.starter.firmware.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 设备拉取任务请求。
 *
 * @param requestId 全局唯一请求 ID，用于幂等去重
 * @param deviceId  拉取任务的设备 ID
 */
public record PullTaskRequest(@NotBlank String requestId, @NotBlank String deviceId) {
}
