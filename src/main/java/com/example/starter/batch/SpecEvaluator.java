package com.example.starter.batch;

import java.math.BigDecimal;
import java.util.List;

/**
 * 特性规范求值器：BigDecimal 精确计算，等号合格，不依赖显示舍入。
 * 纯函数，无状态，可独立单元测试。
 */
public final class SpecEvaluator {

    /**
     * 测量向量允许的最大小数位数。
     */
    public static final int MAX_SCALE = 6;

    private SpecEvaluator() {
    }

    /**
     * 判断完整测量向量是否满足规范全部约束：每条约束的 系数×特性值 之和 ≤ 上界。
     * 调用方须保证 values 数量与特性数一致。
     */
    public static boolean passes(SpecDefinition spec, List<BigDecimal> values) {
        for (SpecDefinition.LinearConstraint constraint : spec.constraints()) {
            BigDecimal sum = BigDecimal.ZERO;
            List<Integer> coefficients = constraint.coefficients();
            for (int i = 0; i < coefficients.size(); i++) {
                sum = sum.add(BigDecimal.valueOf(coefficients.get(i)).multiply(values.get(i)));
            }
            if (sum.compareTo(constraint.upperBound()) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 十进制参数规范化：同值同参（1.0 与 1.00 视为相同），用于幂等指纹。
     */
    public static String canonicalDecimal(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * 校验单个测量值精度：最多 6 位小数。
     */
    public static boolean withinScale(BigDecimal value) {
        return value.scale() <= MAX_SCALE;
    }
}
