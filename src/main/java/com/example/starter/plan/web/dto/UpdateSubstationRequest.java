package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 调整变电所功率上限请求，expectedVersion 必须与当前上限版本一致；
 * 调低上限使现存已发布计划超限则 409，不生效。
 */
public record UpdateSubstationRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotNull @Positive Integer powerCapKw) {
}
