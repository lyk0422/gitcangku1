package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 区段走廊等级登记请求，等级 1～5，数值越大优先级越高。
 */
public record SectionPriorityRequest(@NotNull @Min(1) @Max(5) Integer priority) {
}
