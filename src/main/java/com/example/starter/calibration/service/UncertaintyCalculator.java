package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

import com.example.starter.calibration.model.UncertaintyComponent;

/**
 * 校准不确定度合成口径（公开）：对多个非负分量取平方和后开平方（RSS，root-sum-of-squares），
 * 结果按 HALF_UP 保留 4 位小数返回。分量为零时合成不确定度为 0.0000。
 *
 * <p>平方与开方使用 {@link MathContext#DECIMAL128} 精度（34 位有效数字），
 * 远高于入参的 6 位小数，避免中间截断影响第 4 位小数的舍入。
 */
public final class UncertaintyCalculator {

    /** 对外返回的小数位数。 */
    public static final int SCALE = 4;

    /** 计算口径标识，随放行快照固化。 */
    public static final String FORMULA = "RSS_ROOT_SUM_OF_SQUARES";

    private static final MathContext MC = MathContext.DECIMAL128;

    private UncertaintyCalculator() {
    }

    /**
     * 按分量大小计算合成不确定度。
     *
     * @param magnitudes 非负分量大小列表（可为空，表示尚无分量登记）
     * @return sqrt(Σ x²)，HALF_UP 保留 4 位小数，永不返回 null
     * @throws IllegalArgumentException 任一分量为负或为 null
     */
    public static BigDecimal combine(List<BigDecimal> magnitudes) {
        if (magnitudes == null || magnitudes.isEmpty()) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal sumSquares = BigDecimal.ZERO;
        for (BigDecimal magnitude : magnitudes) {
            if (magnitude == null) {
                throw new IllegalArgumentException("不确定度分量大小不能为 null");
            }
            if (magnitude.signum() < 0) {
                throw new IllegalArgumentException("不确定度分量必须非负: " + magnitude);
            }
            sumSquares = sumSquares.add(magnitude.multiply(magnitude, MC), MC);
        }
        return sqrt(sumSquares).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 按分量记录计算合成不确定度（按预算明细，顺序无关）。
     */
    public static BigDecimal combineComponents(List<UncertaintyComponent> components) {
        if (components == null) {
            return combine(List.of());
        }
        return combine(components.stream().map(UncertaintyComponent::magnitude).toList());
    }

    /**
     * 判定合成不确定度是否满足阈值：combined.compareTo(threshold) &lt;= 0（含端点）。
     */
    public static boolean withinThreshold(BigDecimal combined, BigDecimal threshold) {
        return combined.compareTo(threshold) <= 0;
    }

    /**
     * BigDecimal 平方根（牛顿迭代，DECIMAL128 精度）。入参非负。
     */
    private static BigDecimal sqrt(BigDecimal value) {
        if (value.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal x = BigDecimal.valueOf(Math.sqrt(value.doubleValue()));
        for (int i = 0; i < 20; i++) {
            BigDecimal next = x.add(value.divide(x, MC), MC).divide(BigDecimal.valueOf(2), MC);
            if (next.compareTo(x) == 0) {
                return next;
            }
            x = next;
        }
        return x;
    }
}
