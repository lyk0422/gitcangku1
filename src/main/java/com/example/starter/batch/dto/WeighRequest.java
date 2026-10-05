package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 新增称量请求。mass 单位克，正值且最多 6 位小数；同成分多次称量累加。
 * expectedVersion 为调用方看到的称量链版本，与当前版本不一致时返回 409。
 */
public record WeighRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "ingredient 不能为空") String ingredient,
        @NotNull(message = "mass 不能为空") BigDecimal mass,
        @NotNull(message = "expectedVersion 不能为空") Long expectedVersion
) {
}