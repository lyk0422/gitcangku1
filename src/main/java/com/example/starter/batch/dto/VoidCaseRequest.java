package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 封箱作废请求。reason 为作废原因，非空；仅未放行批次允许作废。
 */
public record VoidCaseRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "reason 不能为空") String reason
) {
}
