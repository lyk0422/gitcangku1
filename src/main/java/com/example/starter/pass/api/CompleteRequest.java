package com.example.starter.pass.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 接收完成回报。
 *
 * @param requestId   完成回报自身的幂等 requestId（与分配 requestId 独立命名空间）
 * @param actualBytes 实际接收字节数，单位：字节，不得为负且不得超过计划量
 */
public record CompleteRequest(
        @NotBlank String requestId,
        @NotNull @PositiveOrZero Long actualBytes
) {
}
