package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 线路区段登记请求：把计划行程中的区段 ID 映射到线路与最低线路等级。
 * 已登记区段重复登记返回 409。
 */
public record LineSectionRequest(
        @NotBlank String lineId,
        @NotNull @PositiveOrZero Integer requiredGrade) {
}
