package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 称量更正请求。correctsWeighingId 必须为该成分最近一条有效称量；
 * 更正以替代量重算该成分累计，而不是重复累加。mass 单位克，正值且最多 6 位小数。
 */
public record CorrectWeighingRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "correctsWeighingId 不能为空") Long correctsWeighingId,
        @NotNull(message = "mass 不能为空") BigDecimal mass,
        @NotNull(message = "expectedVersion 不能为空") Long expectedVersion
) {
}