package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 回退计划设备派发请求：为设备派发当前应执行的下一跳任务。
 */
public record RollbackDispatchRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId) {
}
