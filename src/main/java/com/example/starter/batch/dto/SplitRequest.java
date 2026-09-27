package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 拆分请求体。父批由路径给出；children 为 2～5 个全新子批，各自提供全局唯一的
 * batchKey 与批号 batchNo。子批键已存在或请求内重复时整次 409，父批不变。
 *
 * <p>父批已登记效价模型时必须提供 parentWeight 与每个子批 weight（正数），
 * 且子批权重之和必须等于 parentWeight（权重守恒），否则 422；
 * 父批未登记效价模型时权重字段忽略，维持既有行为。
 */
public record SplitRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        BigDecimal parentWeight,
        @NotNull(message = "children 不能为空")
        @Size(min = 2, max = 5, message = "children 必须包含 2～5 个子批")
        List<@Valid ChildSpec> children
) {

    /**
     * 子批规格：batchKey 全局唯一，batchNo 为子批批号；产品编码、生产时间与必做检验项继承父批。
     * weight 为子批分摊权重（正数），父批登记效价模型时必填。
     */
    public record ChildSpec(
            @NotBlank(message = "子批 batchKey 不能为空") String batchKey,
            @NotBlank(message = "子批 batchNo 不能为空") String batchNo,
            BigDecimal weight
    ) {
    }
}
