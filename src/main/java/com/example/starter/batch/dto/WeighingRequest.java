package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 追加称量请求。amountGrams 单位克、正值、最多6位小数；同成分多次称量累加。
 * expectedVersion 为批次当前称量链版本，不一致返回409；requestId 为幂等键。
 */
public record WeighingRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "ingredient 不能为空") String ingredient,
        @NotNull(message = "amountGrams 不能为空") BigDecimal amountGrams,
        @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion
) {
}