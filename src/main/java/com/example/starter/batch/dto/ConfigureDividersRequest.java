package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 隔板配置请求；仅未装运（OPEN）托盘可修改，数量变更产生新隔板版本，不追溯影响已装运快照。
 */
public record ConfigureDividersRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "dividerCount 不能为空")
        @Min(value = 0, message = "dividerCount 不能为负") @Max(value = 99, message = "dividerCount 不能超过 99")
        Integer dividerCount
) {
}
