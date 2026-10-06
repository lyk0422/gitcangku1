package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 修改区段防护档案请求，expectedVersion 必须与当前配置版本一致。
 */
public record SectionProfileUpdateRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull @Positive @Digits(integer = 15, fraction = 3) BigDecimal protectDistanceD,
        @NotNull Integer expectedVersion) {
}
