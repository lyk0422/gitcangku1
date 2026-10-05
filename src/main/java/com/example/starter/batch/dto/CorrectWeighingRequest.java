package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 称量更正请求。weighingId 必须为该成分最近一条有效称量记录；
 * amount 为替代值（单位克，正值、最多 6 位小数），按替代量重算而非累加。
 */
public record CorrectWeighingRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "weighingId 不能为空") Long weighingId,
        @NotNull(message = "amount 不能为空") BigDecimal amount,
        @NotNull(message = "expectedVersion 不能为空") Long expectedVersion
) {
}
