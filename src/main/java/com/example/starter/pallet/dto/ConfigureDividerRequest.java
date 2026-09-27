package com.example.starter.pallet.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 变更托盘隔板配置请求。每次变更隔板版本 +1；已装运快照不追溯。
 */
public record ConfigureDividerRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "dividerCount 不能为空")
        @Min(value = 0, message = "dividerCount 不能小于 0")
        @Max(value = 8, message = "dividerCount 不能大于 8") Integer dividerCount
) {
}
