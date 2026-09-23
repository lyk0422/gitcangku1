package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 回退计划人工恢复请求：仅 PAUSED 可恢复，开启新监控轮次并只重派该跳未成功设备。
 */
public record ResumeRollbackPlanRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 256) String reason) {
}
