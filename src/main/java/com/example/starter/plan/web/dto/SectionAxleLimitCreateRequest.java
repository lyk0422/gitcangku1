package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记区段轴限档案请求。requestId 为全局幂等键，operator 为必填操作者；
 * 限值均为正整数千克。
 */
public record SectionAxleLimitCreateRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull @Positive Long singleAxleLimitKg,
        @NotNull @Positive Long window3LimitKg) {
}
