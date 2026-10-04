package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 称量更正请求：指定该成分最近有效称量ID，以替代量重算而不是重复累加。
 * massGrams 单位克、不得为负、最多 6 位小数；ingredient 必须与被更正记录一致。
 * expectedVersion 为客户端期望的当前称量链版本，不一致返回 409。
 */
public record CorrectWeighingRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "replacesWeighingId 不能为空") Long replacesWeighingId,
        @NotBlank(message = "ingredient 不能为空") String ingredient,
        @NotNull(message = "massGrams 不能为空") BigDecimal massGrams,
        @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion
) {
}