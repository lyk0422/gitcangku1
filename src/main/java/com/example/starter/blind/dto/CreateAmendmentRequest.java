package com.example.starter.blind.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 创建协议修订请求体（支持单条或批量）。
 * 比例均为正整数且总和 100；生效时刻为 Unix 毫秒 UTC，不得早于当前时刻。
 *
 * @param ratioA      A 组区组比例（百分比），1~99
 * @param ratioB      B 组区组比例（百分比），1~99，与 ratioA 之和为 100
 * @param effectiveAt 生效时刻，Unix 毫秒，UTC
 */
public record CreateAmendmentRequest(
        @NotNull(message = "ratioA 不能为空")
        @Positive(message = "比例必须为正整数")
        Integer ratioA,
        @NotNull(message = "ratioB 不能为空")
        @Positive(message = "比例必须为正整数")
        Integer ratioB,
        @NotNull(message = "effectiveAt 不能为空")
        Long effectiveAt
) {

    /** 批量创建修订；只允许一条最终待生效版本。 */
    public record Batch(
            @NotNull(message = "amendments 不能为空")
            @Valid
            List<CreateAmendmentRequest> amendments) {
    }
}
