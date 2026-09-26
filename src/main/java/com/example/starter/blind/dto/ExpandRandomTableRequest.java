package com.example.starter.blind.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 区组扩容请求体：只能新建后继随机表版本，必须显式引用已封存版本。
 *
 * @param predecessorVersionId 已封存的前驱随机表版本主键，必须是该区组当前最新版本
 * @param addedSeats           新增席位数，正偶数（保持处理代码两半均衡）
 * @param expectedUnallocated  调用方声明的扩容后未分配名额；
 *                             与 实际（前驱容量+新增席位-已分配） 不守恒时整次 422
 */
public record ExpandRandomTableRequest(
        @NotNull(message = "predecessorVersionId 不能为空")
        Long predecessorVersionId,

        @NotNull(message = "addedSeats 不能为空")
        @Min(value = 2, message = "addedSeats 必须为不小于 2 的正偶数")
        Integer addedSeats,

        @NotNull(message = "expectedUnallocated 不能为空")
        Long expectedUnallocated
) {
}
