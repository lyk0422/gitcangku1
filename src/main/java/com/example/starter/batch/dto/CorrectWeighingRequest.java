package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 称量更正请求：指定最近有效称量ID（weighingId），以替代量重算而不是重复累加。
 * 被更正行失效，新行继承其成分；amountGrams 单位克、正值、最多6位小数。
 */
public record CorrectWeighingRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "weighingId 不能为空") Long weighingId,
        @NotNull(message = "amountGrams 不能为空") BigDecimal amountGrams,
        @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion
) {
}