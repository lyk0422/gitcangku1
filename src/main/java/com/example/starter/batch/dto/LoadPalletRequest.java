package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 托盘装载请求。
 * entries 为规范排序批次集合（顺序即响应与快照排序），每项指定批次与分配分隔位 slotNo（从 1 开始）；
 * 分隔位在请求内与托盘当前 ACTIVE 装载中均不得重复。
 */
public record LoadPalletRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "entries 不能为空")
        @Size(min = 1, max = 50, message = "entries 必须包含 1～50 个批次")
        List<@NotNull(message = "装载项不能为空") Entry> entries
) {

    /**
     * 单个装载项：批次业务键 + 分隔位编号。
     */
    public record Entry(
            @NotBlank(message = "batchKey 不能为空") String batchKey,
            @NotNull(message = "slotNo 不能为空")
            @Positive(message = "slotNo 必须为正整数")
            Integer slotNo
    ) {
    }
}
