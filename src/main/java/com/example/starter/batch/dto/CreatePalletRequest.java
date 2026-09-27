package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 创建托盘请求；dividerCount 为初始有效隔板数量，默认 0，取值 0～99。
 */
public record CreatePalletRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "palletKey 不能为空") String palletKey,
        @Min(value = 0, message = "dividerCount 不能为负") @Max(value = 99, message = "dividerCount 不能超过 99")
        Integer dividerCount
) {
}
