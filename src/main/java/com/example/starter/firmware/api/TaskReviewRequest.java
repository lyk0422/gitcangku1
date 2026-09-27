package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 撤销链回执的人工复核请求：CONFIRMED 人工确认（SUCCESS 回执据此更新设备版本）；REJECTED 人工拒绝。
 */
public record TaskReviewRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReviewAction action,
        @NotBlank @Size(max = 256) String reason,
        @NotBlank @Size(max = 64) String operator) {

    /**
     * 复核动作。
     */
    public enum ReviewAction {
        CONFIRMED,
        REJECTED
    }
}
