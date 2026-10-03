package com.example.starter.allocation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 接收完成回报。
 *
 * @param requestId 完成回报幂等键，同键同参重放返回原结果，同键异参返回 409
 * @param actualBytes 实际接收字节数，不得超过计划数据量
 */
public record CompletionRequest(
        @NotBlank String requestId,
        @PositiveOrZero long actualBytes) {
}