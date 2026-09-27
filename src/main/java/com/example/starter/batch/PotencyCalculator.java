package com.example.starter.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 效价线性衰减与数量加权计算（纯函数，便于单元测试）。
 *
 * <p>所有时间为 UTC；区间左闭右开：锚点时刻经过 0 个整日，不满一日按秒级比例连续衰减。
 * 效价结果统一按 {@link #SCALE} 位小数、HALF_UP 舍入；中间经过天数保留 {@link #DAY_SCALE} 位。
 */
public final class PotencyCalculator {

    /**
     * 效价结果小数位：舍入结果与库内 DECIMAL(28,6) 一致。
     */
    public static final int SCALE = 6;

    /**
     * 经过 UTC 日数的中间精度。
     */
    public static final int DAY_SCALE = 18;

    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86400L);
    private static final BigDecimal NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);

    private PotencyCalculator() {
    }

    /**
     * 锚点到计算时刻经过的 UTC 日数（秒级连续，非整日截断）；at 早于 anchor 时返回负数。
     */
    public static BigDecimal elapsedDays(Instant anchor, Instant at) {
        Duration duration = Duration.between(anchor, at);
        BigDecimal seconds = BigDecimal.valueOf(duration.getSeconds())
                .add(BigDecimal.valueOf(duration.getNano(), 9));
        return seconds.divide(SECONDS_PER_DAY, DAY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 模型在指定时刻的当前效价：initial - rate * days，结果 HALF_UP 保留 6 位。
     * 线性模型不做下限截断（允许算成负值，放行校验据此判定不合格）。
     */
    public static BigDecimal currentPotency(PotencyModel model, Instant at) {
        BigDecimal days = elapsedDays(model.anchor(), at);
        return model.initialPotency()
                .subtract(model.dailyDecayRate().multiply(days))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 数量加权平均：Σ(weight_i * value_i) / Σ(weight_i)，结果 HALF_UP 保留 6 位。
     * 调用方须保证 weights 非空且总权重大于 0。
     */
    public static BigDecimal weightedAverage(List<BigDecimal> weights, List<BigDecimal> values) {
        if (weights.size() != values.size() || weights.isEmpty()) {
            throw new IllegalArgumentException("权重与数值数量为空或不一致");
        }
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (int i = 0; i < weights.size(); i++) {
            totalWeight = totalWeight.add(weights.get(i));
            weightedSum = weightedSum.add(weights.get(i).multiply(values.get(i)));
        }
        if (totalWeight.signum() <= 0) {
            throw new IllegalArgumentException("权重合计必须为正");
        }
        return weightedSum.divide(totalWeight, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 差额：actual - required（正数表示富余，负数表示缺口），6 位 HALF_UP。
     */
    public static BigDecimal margin(BigDecimal actual, BigDecimal required) {
        return actual.subtract(required).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 判断舍入后的结果效价是否不低于阈值。
     */
    public static boolean eligible(BigDecimal roundedResult, BigDecimal threshold) {
        return roundedResult.compareTo(threshold) >= 0;
    }
}
