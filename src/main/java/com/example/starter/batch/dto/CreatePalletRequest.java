package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建托盘请求。dividerCount 为隔板数量（0～8），可用分隔位数 = 隔板数量 + 1。
 */
public record CreatePalletRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "palletKey 不能为空") String palletKey,
        @NotNull(message = "dividerCount 不能为空")
        @Min(value = 0, message = "dividerCount 不能小于 0")
        @Max(value = 8, message = "dividerCount 不能大于 8")
        Integer dividerCount
) {
}
