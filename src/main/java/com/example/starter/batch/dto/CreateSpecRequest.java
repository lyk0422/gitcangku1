package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建特性规范新版本请求。规范固定 2～5 个无量纲合成特性及 1～8 条线性不等式约束；
 * 版本只追加：version 必须严格大于该规范已有最大版本。
 */
public record CreateSpecRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotBlank(message = "specKey 不能为空") String specKey,
        @NotNull(message = "version 不能为空") @Min(value = 1, message = "version 必须为正整数") Integer version,
        @NotNull(message = "features 不能为空")
        @Size(min = 2, max = 5, message = "features 必须包含 2～5 个特性")
        List<@NotBlank(message = "特性名不能为空") String> features,
        @NotNull(message = "constraints 不能为空")
        @Size(min = 1, max = 8, message = "constraints 必须包含 1～8 条约束")
        List<@Valid ConstraintSpec> constraints
) {

    /**
     * 单条线性不等式：整数系数乘特性之和不大于精确十进制上界（等号合格）。
     * coefficients 与 features 等长一一对应；系数范围 -100～100 且不得全零。
     */
    public record ConstraintSpec(
            @NotNull(message = "coefficients 不能为空")
            @Size(min = 2, max = 5, message = "coefficients 必须包含 2～5 个系数")
            List<@NotNull(message = "系数不能为空")
            @Min(value = -100, message = "系数不得小于 -100") @Max(value = 100, message = "系数不得大于 100")
                    Integer> coefficients,
            @NotNull(message = "upperBound 不能为空") BigDecimal upperBound
    ) {
    }
}
