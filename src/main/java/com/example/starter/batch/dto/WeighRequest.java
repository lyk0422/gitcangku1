package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 称量请求。ingredient 必须属于批次绑定的配方版本；mass 单位克，正值，最多 6 位小数；
 * expectedVersion 为客户端期望的称量链版本号，与当前不一致时返回 409。
 */
public record WeighRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "ingredient 不能为空") String ingredient,
        @NotNull(message = "mass 不能为空") BigDecimal mass,
        @NotNull(message = "expectedVersion 不能为空") Long expectedVersion
) {
}
