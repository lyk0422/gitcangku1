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
 * 父批已绑定配方时，每个子批必须提供 targetMass（单位克，正值、最多 6 位小数），
 * 各子批 targetMass 之和须精确等于父批目标总质量，且父批各成分质量须能按子批
 * 质量比例在 6 位小数内精确分摊，否则整次 422；父批未绑定配方时不得提供 targetMass。
 */
public record SplitRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "children 不能为空")
        @Size(min = 2, max = 5, message = "children 必须包含 2～5 个子批")
        List<@Valid ChildSpec> children
) {

    /**
     * 子批规格：batchKey 全局唯一，batchNo 为子批批号；产品编码、生产时间与必做检验项继承父批。
     * targetMass 仅在父批已绑定配方时提供，为子批目标总质量（单位克）。
     */
    public record ChildSpec(
            @NotBlank(message = "子批 batchKey 不能为空") String batchKey,
            @NotBlank(message = "子批 batchNo 不能为空") String batchNo,
            BigDecimal targetMass
    ) {
    }
}