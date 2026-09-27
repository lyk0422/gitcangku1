package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工确认复核请求：确认后 SUCCESS 任务才完成设备版本更新。
 */
public record ConfirmReviewRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator) {
}
