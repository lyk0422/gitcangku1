package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合批请求体：2～5 个已放行父批按权重合并为一个全新子批。
 * 权重不守恒（父批权重之和 ≠ 子批权重）、父批未检验/未放行、父批未登记效价模型、
 * 任一父批当前效价低于其放行阈值均返回 422 且整次回滚。
 */
public record MergeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "parents 不能为空")
        @Size(min = 2, max = 5, message = "parents 必须包含 2～5 个父批")
        List<@Valid ParentSpec> parents,
        @NotNull(message = "child 不能为空") @Valid ChildSpec child
) {

    /**
     * 父批参与项：batchKey 必须已放行；weight 为该父批参与合批的权重（正数）。
     */
    public record ParentSpec(
            @NotBlank(message = "父批 batchKey 不能为空") String batchKey,
            @NotNull(message = "父批 weight 不能为空") BigDecimal weight
    ) {
    }

    /**
     * 合批子批规格：batchKey 全局唯一；weight 为子批总权重，必须等于全部父批权重之和。
     * 产品编码、生产时间与必做检验项继承自规范化排序后的首个父批。
     */
    public record ChildSpec(
            @NotBlank(message = "子批 batchKey 不能为空") String batchKey,
            @NotBlank(message = "子批 batchNo 不能为空") String batchNo,
            @NotNull(message = "子批 weight 不能为空") BigDecimal weight
    ) {
    }
}
