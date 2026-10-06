package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 调整变电所功率上限请求，expectedVersion 必须与当前版本一致；
 * 调低上限使现存已发布计划超限则 409。
 */
public record UpdateSubstationRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull @Min(1) Integer powerLimitKw,
        @NotNull Integer expectedVersion) {
}
