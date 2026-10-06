package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 创建区段轴重限值档案请求。requestId 为全局幂等键，operator 为操作者（非空）；
 * 限值单位千克，正数。
 */
public record SectionLimitProfileCreateRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String sectionId,
        @NotNull @Positive Long singleAxleLimitKg,
        @NotNull @Positive Long window3LimitKg) {
}
