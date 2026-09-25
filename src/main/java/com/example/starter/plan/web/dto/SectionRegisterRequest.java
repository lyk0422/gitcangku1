package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 登记区段走廊等级请求，等级仅可取 1～5（数值越大优先级越高）。
 */
public record SectionRegisterRequest(
        @NotNull @Min(1) @Max(5) Integer priority) {
}
