package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 托盘装载请求。items 为规范排序的批次集合（顺序即存储顺序），每项声明隔离类别与分隔位。
 * 同一分隔位内类别必须一致；不同类别必须分配到不同分隔位；
 * 任一项校验失败整批 422，不写入任何托盘关系。
 */
public record LoadRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "items 不能为空")
        @Size(min = 1, max = 8, message = "items 必须包含 1～8 个批次")
        List<@Valid LoadItem> items
) {

    /**
     * 装载项：batchKey 全局唯一批次；category 为该批次声明的隔离类别；
     * compartmentNo 为分隔位编号，从 1 开始，不超过托盘 隔板数量+1。
     */
    public record LoadItem(
            @NotBlank(message = "batchKey 不能为空") String batchKey,
            @NotBlank(message = "category 不能为空")
            @Size(max = 32, message = "category 长度不能超过 32")
            String category,
            @NotNull(message = "compartmentNo 不能为空")
            @Min(value = 1, message = "compartmentNo 从 1 开始")
            @Max(value = 9, message = "compartmentNo 不能超过 9")
            Integer compartmentNo
    ) {
    }
}
