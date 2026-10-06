package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * QUANTIFIED 批按数量拆分请求体。父批由路径给出；children 为 2～5 个全新子批，
 * 各自提供全局唯一 batchKey、批号 batchNo 与正整数 quantity。
 * 全部子批 quantity 之和必须等于父批剩余量（初始数量 - 累计消耗量），否则整次 422；
 * 子批键已存在或请求内重复时整次 409，父批不变。
 */
public record SplitQuantitiesRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "children 不能为空")
        @Size(min = 2, max = 5, message = "children 必须包含 2～5 个子批")
        List<@Valid QuantifiedChildSpec> children
) {

    /**
     * 数量子批规格：quantity 为子批初始数量（正整数，件）；maxSampleAmount 为子批不可变
     * 最大取样量，缺省为 0，取值 0～quantity；产品编码、生产时间与必做检验项继承父批。
     */
    public record QuantifiedChildSpec(
            @NotBlank(message = "子批 batchKey 不能为空") String batchKey,
            @NotBlank(message = "子批 batchNo 不能为空") String batchNo,
            @NotNull(message = "子批 quantity 不能为空")
            @Positive(message = "子批 quantity 必须为正整数") Integer quantity,
            @Min(value = 0, message = "子批 maxSampleAmount 不得为负") Integer maxSampleAmount
    ) {
    }
}
