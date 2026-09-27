package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工拒绝撤销链回执：记录拒绝原因，设备版本永不更新。
 */
public record RejectReviewRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 256) String reason,
        @NotBlank @Size(max = 64) String operator) {
}
