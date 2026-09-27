package com.example.starter.pallet.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 托盘装载请求：一次装载 1～10 个批次；服务端按 batchKey 规范排序后统一校验并一次提交。
 */
public record LoadBatchesRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "items 不能为空")
        @Size(min = 1, max = 10, message = "items 必须包含 1～10 个装载项")
        List<@Valid Item> items
) {
    /**
     * 单个装载项：批次业务键、声明的隔离类别、目标分隔位（从 1 开始）。
     */
    public record Item(
            @NotBlank(message = "batchKey 不能为空") String batchKey,
            @NotBlank(message = "category 不能为空")
            @Size(max = 64, message = "category 长度不能超过 64") String category,
            @NotNull(message = "compartment 不能为空")
            @Min(value = 1, message = "compartment 必须大于等于 1") Integer compartment
    ) {
    }
}
