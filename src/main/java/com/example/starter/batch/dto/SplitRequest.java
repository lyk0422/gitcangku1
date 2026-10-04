package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 拆分请求体。父批由路径给出；children 为 2至5 个全新子批，各自提供全局唯一的
 * batchKey 与批号 batchNo。子批键已存在或请求内重复时整次 409，父批不变。
 * 父批绑定配方时，每个子批必须提供 targetMassGrams（单位克、正值、最多6位小数），
 * 全部子批目标质量之和须等于父批目标质量，且各成分按子批质量比例继承能在6位小数内
 * 精确守恒，否则整次 422。
 */
public record SplitRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "children 不能为空")
        @Size(min = 2, max = 5, message = "children 必须包含 2至5 个子批")
        List<@Valid ChildSpec> children
) {

    /**
     * 子批规格：batchKey 全局唯一，batchNo 为子批批号；产品编码、生产时间与必做检验项继承父批。
     * targetMassGrams 仅在父批绑定配方时必填（单位克、最多6位小数）。
     */
    public record ChildSpec(
            @NotBlank(message = "子批 batchKey 不能为空") String batchKey,
            @NotBlank(message = "子批 batchNo 不能为空") String batchNo,
            BigDecimal targetMassGrams
    ) {
    }
}