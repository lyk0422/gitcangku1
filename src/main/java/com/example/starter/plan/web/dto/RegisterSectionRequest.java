package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登记走廊区段等级请求。requestKey 为幂等键；priority 取值 1～5，数值越大等级越高。
 */
public record RegisterSectionRequest(
        @NotBlank String requestKey,
        @NotBlank String sectionId,
        @NotNull @Min(1) @Max(5) Integer priority) {
}
