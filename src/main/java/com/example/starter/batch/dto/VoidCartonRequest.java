package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 作废封箱请求：reason 必填并写入封箱记录，同时释放数量与标签占用。
 */
public record VoidCartonRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "reason 不能为空") String reason
) {
}
