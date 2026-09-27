package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量作废请求：一次作废 1～200 个标签的当前生效封箱；仅未放行批次可作废。
 */
public record VoidSealsRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "voids 不能为空")
        @Size(min = 1, max = 200, message = "voids 必须包含 1～200 个作废条目")
        List<@Valid VoidItem> voids
) {
    /**
     * 单个作废条目：labelNo 须存在该批次的生效封箱；reason 写入历史证据。
     */
    public record VoidItem(
            @NotNull(message = "labelNo 不能为空")
            @Positive(message = "labelNo 必须为正整数") Long labelNo,
            @NotBlank(message = "reason 不能为空") String reason
    ) {
    }
}
