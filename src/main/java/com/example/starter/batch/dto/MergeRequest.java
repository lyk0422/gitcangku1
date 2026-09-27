package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合批请求体：2～5 个已放行且已登记效价的父批整体并入一个全新子批。
 * 每个父批的 weight 必须等于其登记数量（父批整体并入），全部 weight 之和必须等于
 * 子批 quantity（权重守恒），否则整次 422 且不产生任何部分结果。
 * 子批初始效价为所有父批在合批时刻效价的数量加权平均（按当前模型版本舍入）。
 */
public record MergeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "batchKey 不能为空") String batchKey,
        @NotBlank(message = "batchNo 不能为空") String batchNo,
        @NotNull(message = "quantity 不能为空") BigDecimal quantity,
        @NotNull(message = "parents 不能为空")
        @Size(min = 2, max = 5, message = "parents 必须包含 2～5 个父批")
        List<@Valid ParentSpec> parents
) {

    /**
     * 合批父批规格：batchKey 必须已存在；weight 为该父批并入的权重（数量），必须等于父批登记数量。
     */
    public record ParentSpec(
            @NotBlank(message = "父批 batchKey 不能为空") String batchKey,
            @NotNull(message = "父批 weight 不能为空") BigDecimal weight
    ) {
    }
}
