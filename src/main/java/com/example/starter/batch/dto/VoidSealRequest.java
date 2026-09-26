package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 作废封箱请求：reason 必填并写入历史，作废后释放数量与标签占用。
 */
public record VoidSealRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "reason 不能为空") String reason
) {
}
