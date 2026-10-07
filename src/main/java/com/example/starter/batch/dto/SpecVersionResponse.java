package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 规范版本响应：完整特性与约束定义。createdAt 为 UTC instant。
 */
public record SpecVersionResponse(
        String specKey,
        int version,
        List<String> features,
        List<ConstraintView> constraints,
        String operator,
        Instant createdAt
) {

    /**
     * 约束视图：整数系数与精确十进制上界。
     */
    public record ConstraintView(List<Integer> coefficients, BigDecimal upperBound) {
    }
}
