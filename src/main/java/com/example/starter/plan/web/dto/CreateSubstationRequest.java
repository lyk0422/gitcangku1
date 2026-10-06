package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 创建变电所请求。requestId 为全局幂等键，operator 为操作者；功率上限为正整数千瓦。
 */
public record CreateSubstationRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String substationKey,
        @NotNull @Positive Integer powerCapKw) {
}
