package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 修改托盘隔板请求。每次修改隔板版本 +1；已装运托盘的修改不追溯影响装运快照。
 */
public record DividerUpdateRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "dividerCount 不能为空")
        @Min(value = 0, message = "dividerCount 不能小于 0")
        @Max(value = 8, message = "dividerCount 不能大于 8")
        Integer dividerCount
) {
}
