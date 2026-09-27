package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 封箱作废请求：仅未放行（无放行快照）批次可作废，作废写入原因并释放数量与标签占用。
 */
public record VoidSealRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "reason 不能为空") String reason
) {
}
