package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * 区段防护档案写入请求（创建或修改）。
 * requestId 为幂等键；operator 为操作者；expectedVersion 为 0 表示创建（档案须不存在），
 * 大于 0 表示修改且必须与当前版本一致；d 为正十进制、最多3位小数，单位米。
 */
public record SectionProfileRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @NotNull @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 9, fraction = 3) BigDecimal protectionDistance) {
}
