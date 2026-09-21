package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 召回请求体；操作人通过 X-Actor-Id 请求头提供。
 */
public record RecallRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "reason 不能为空") String reason
) {
}
