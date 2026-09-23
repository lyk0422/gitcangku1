package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 将设备首次分配到队列的请求（分配版本与指令代次均从 1 开始）。
 */
public record AssignDeviceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) long releaseId,
        @NotBlank @Size(max = 64) String deviceId,
        @Min(1) long cohortId) {
}
