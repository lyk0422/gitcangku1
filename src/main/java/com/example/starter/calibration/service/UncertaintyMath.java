package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * 合成不确定度计算：公开口径为“平方和开根”（Root Sum of Squares），
 * 结果按 HALF_UP 保留 4 位小数返回；与阈值比较时同样使用该 4 位小数值。
 */
public final class UncertaintyMath {

    /** 计算口径标识，随预算与放行快照固化。 */
    public static final String CALC_SPEC = "RSS-4DP-HALF_UP";

    /** 合成不确定度小数位数。 */
    public static final int SCALE = 4;

    private static final MathContext SQRT_CONTEXT = new MathContext(34, RoundingMode.HALF_UP);

    private UncertaintyMath() {
    }

    /**
     * 平方和开根：u = sqrt(Σ uᵢ²)，HALF_UP 保留 4 位小数；空分量集合返回 0.0000。
     */
    public static BigDecimal combine(List<BigDecimal> values) {
        BigDecimal sumOfSquares = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            sumOfSquares = sumOfSquares.add(value.multiply(value));
        }
        return sumOfSquares.sqrt(SQRT_CONTEXT).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 分量平方和（未开根），用于诊断查询的贡献度计算。
     */
    public static BigDecimal sumOfSquares(List<BigDecimal> values) {
        BigDecimal sumOfSquares = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            sumOfSquares = sumOfSquares.add(value.multiply(value));
        }
        return sumOfSquares;
    }
}
