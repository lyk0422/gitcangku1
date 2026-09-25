package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备拉取请求：携带 requestId，可选紧急例外凭据。
 * 命中生效冻结窗口且无完整双人例外时返回 422，不创建任务。
 */
public record PullRequest(@NotBlank @Size(max = 64) String requestId, EmergencyGrant emergencyGrant) {
}
