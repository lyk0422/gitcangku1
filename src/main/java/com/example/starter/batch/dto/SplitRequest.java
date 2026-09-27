package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 拆分请求体。父批由路径给出；children 为 2～5 个全新子批，各自提供全局唯一的
 * batchKey、批号 batchNo 与分摊数量 quantity（父批登记数量时必填，为正）。
 * 子批分摊数量之和必须严格等于父批操作时刻数量，否则 422 且整次回滚；
 * 子批键已存在或请求内重复时整次 409，父批不变。
 */
public record SplitRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "children 不能为空")
        @Size(min = 2, max = 5, message = "children 必须包含 2～5 个子批")
        List<@Valid ChildSpec> children
) {

    /**
     * 子批规格：batchKey 全局唯一，batchNo 为子批批号；产品编码、生产时间与必做检验项继承父批。
     * quantity 为子批分摊数量（无量纲，正数）；父批未登记数量时必须省略。
     */
    public record ChildSpec(
            @NotBlank(message = "子批 batchKey 不能为空") String batchKey,
            @NotBlank(message = "子批 batchNo 不能为空") String batchNo,
            BigDecimal quantity
    ) {
    }
}
