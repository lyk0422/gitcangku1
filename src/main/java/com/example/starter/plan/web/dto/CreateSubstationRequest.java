package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建变电所请求。requestId 为全局幂等键，operator 为操作者，均非空；
 * 功率上限为正整数千瓦，版本从 1 起。
 */
public record CreateSubstationRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String substationKey,
        @NotNull @Min(1) Integer powerLimitKw) {
}
