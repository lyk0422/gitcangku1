package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 配方称量请求。ingredient 必须属于批次绑定配方；massG 单位克，最多 6 位小数、非负；
 * expectedVersion 可空，非空时必须等于批次当前称量链版本（称量记录总数），否则 409。
 */
public record WeighRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotBlank(message = "ingredient 不能为空") String ingredient,
        @NotNull(message = "massG 不能为空") BigDecimal massG,
        Long expectedVersion
) {
}
