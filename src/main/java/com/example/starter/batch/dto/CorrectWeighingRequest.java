package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 称量更正请求。weighingId 必须为该成分当前最近的有效称量记录ID；
 * newMassG 为替代量（重算而非累加），单位克，最多 6 位小数、非负；
 * expectedVersion 可空，非空时必须等于批次当前称量链版本，否则 409。
 */
public record CorrectWeighingRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotNull(message = "weighingId 不能为空") Long weighingId,
        @NotNull(message = "newMassG 不能为空") BigDecimal newMassG,
        Long expectedVersion
) {
}
