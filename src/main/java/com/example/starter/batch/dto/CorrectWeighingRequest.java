package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 称量更正请求。weighingId 必须为该成分最近一条有效称量记录；
 * 更正以新记录替代被引用记录（替代量重算，不重复累加），被替代记录随即失效。
 */
public record CorrectWeighingRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "weighingId 不能为空") Long weighingId,
        @NotNull(message = "mass 不能为空") BigDecimal mass,
        @NotNull(message = "expectedVersion 不能为空") Long expectedVersion
) {
}
