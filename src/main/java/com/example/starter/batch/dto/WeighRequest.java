package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 称量请求：同成分多次称量累加。massGrams 单位克、正值、最多 6 位小数；
 * expectedVersion 为客户端期望的当前称量链版本（未发生过称量时为 0），不一致返回 409。
 * 操作人通过 X-Actor-Id 请求头提供。
 */
public record WeighRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "ingredient 不能为空") String ingredient,
        @NotNull(message = "massGrams 不能为空") BigDecimal massGrams,
        @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion
) {
}