package com.example.starter.batch;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 效价衰减纯计算：线性衰减、数量加权平均与按模型版本舍入。
 * 所有时间按 UTC 日左闭右开区间计整天数；计算不依赖外部状态，便于独立验证。
 */
public final class PotencyCalculator {

    private PotencyCalculator() {
    }

    /**
     * 已衰减的 UTC 整天数：epoch 与 now 的 UTC 日期之差。
     * 区间左闭右开：epoch 当天计 0，进入下一个 UTC 日 00:00 起计 1。
     */
    public static long elapsedUtcDays(Instant epoch, Instant now) {
        return ChronoUnit.DAYS.between(epoch.atOffset(ZoneOffset.UTC).toLocalDate(),
                now.atOffset(ZoneOffset.UTC).toLocalDate());
    }

    /**
     * 线性衰减：initial - ratePerDay × elapsedUtcDays（DECIMAL128 精度，未舍入）。
     */
    public static BigDecimal currentPotency(BigDecimal initial, BigDecimal ratePerDay,
                                            Instant epoch, Instant now) {
        long days = elapsedUtcDays(epoch, now);
        return initial.subtract(ratePerDay.multiply(BigDecimal.valueOf(days), MathContext.DECIMAL128),
                MathContext.DECIMAL128);
    }

    /**
     * 数量加权平均：Σ(potency × weight) / Σweight（DECIMAL128 精度，未舍入）。
     */
    public static BigDecimal weightedAverage(List<BigDecimal> potencies, List<BigDecimal> weights) {
        if (potencies.size() != weights.size() || potencies.isEmpty()) {
            throw new IllegalArgumentException("效价与权重列表必须同长且非空");
        }
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < potencies.size(); i++) {
            sum = sum.add(potencies.get(i).multiply(weights.get(i), MathContext.DECIMAL128),
                    MathContext.DECIMAL128);
            total = total.add(weights.get(i), MathContext.DECIMAL128);
        }
        return sum.divide(total, MathContext.DECIMAL128);
    }

    /**
     * 按模型版本舍入：scale 为小数位数，roundingMode 为 java.math.RoundingMode 名称。
     */
    public static BigDecimal round(BigDecimal raw, int scale, String roundingMode) {
        return raw.setScale(scale, RoundingMode.valueOf(roundingMode));
    }
}
