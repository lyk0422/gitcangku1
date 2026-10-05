package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 称量请求。amount 单位克，正值、最多 6 位小数；同成分多次称量累加。
 * expectedVersion 为调用方看到的称量链版本，与服务端不一致时返回 409。
 */
public record WeighingRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "ingredient 不能为空") String ingredient,
        @NotNull(message = "amount 不能为空") BigDecimal amount,
        @NotNull(message = "expectedVersion 不能为空") Long expectedVersion
) {
}
