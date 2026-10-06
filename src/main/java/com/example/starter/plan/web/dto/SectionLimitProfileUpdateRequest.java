package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 更新区段轴重限值档案请求，expectedVersion 必须与当前版本一致；
 * 若更新使任何已发布计划非法则 409，版本不变。
 */
public record SectionLimitProfileUpdateRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotNull @Positive Long singleAxleLimitKg,
        @NotNull @Positive Long window3LimitKg) {
}
