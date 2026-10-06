package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 拆分请求体。父批由路径给出；children 为 2～5 个全新子批，各自提供全局唯一的
 * batchKey 与批号 batchNo。子批键已存在或请求内重复时整次 409，父批不变。
 * 新类型数量批次拆分时每个子批必须携带正整数 quantity，
 * 且子批数量之和必须等于父批总数量减去累计消耗；普通批次不得携带 quantity。
 */
public record SplitRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "children 不能为空")
        @Size(min = 2, max = 5, message = "children 必须包含 2～5 个子批")
        List<@Valid ChildSpec> children
) {

    /**
     * 子批规格：batchKey 全局唯一，batchNo 为子批批号；产品编码、生产时间与必做检验项继承父批。
     * quantity 仅新类型数量批次拆分必填（正整数），普通批次必须为 null。
     */
    public record ChildSpec(
            @NotBlank(message = "子批 batchKey 不能为空") String batchKey,
            @NotBlank(message = "子批 batchNo 不能为空") String batchNo,
            @Positive(message = "子批 quantity 必须为正整数") Integer quantity
    ) {
    }
}
