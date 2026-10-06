package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 修改区段轴限档案请求，expectedVersion 必须与当前版本一致；限值均为正整数千克。
 */
public record SectionAxleLimitUpdateRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotNull @Positive Long singleAxleLimitKg,
        @NotNull @Positive Long window3LimitKg) {
}
