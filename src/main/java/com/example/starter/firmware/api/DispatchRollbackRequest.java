package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 回退波次派发请求：设备主动拉取当前跳当前轮次任务；PAUSED 后及后续跳停止派发。
 */
public record DispatchRollbackRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String deviceId) {
}
