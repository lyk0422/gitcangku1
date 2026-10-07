package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 线性不等式约束：整数系数（-100～100，不全为零）乘特性之和不大于精确十进制上界，等号合格。
 * coefficients 顺序与规范 features 顺序一一对应。
 */
public record SpecConstraintInput(
        @NotNull(message = "coefficients 不能为空") List<Integer> coefficients,
        @NotNull(message = "bound 不能为空") BigDecimal bound
) {
}
