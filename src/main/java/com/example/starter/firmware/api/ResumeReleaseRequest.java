package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工恢复请求：expectedVersion 必须等于发布单当前版本，成功加一并开启新监控轮次。
 */
public record ResumeReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) int expectedVersion,
        @NotBlank @Size(max = 256) String reason) {
}
