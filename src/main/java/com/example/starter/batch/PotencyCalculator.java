package com.example.starter.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 效价衰减纯函数计算：当前效价 = 初始效价 - 每 UTC 日衰减率 × 经过 UTC 整日数。
 *
 * <p>经过天数按 UTC 日历日计算，区间端点左闭右开：跨入某一 UTC 日 00:00 即计入该日；
 * 计算时刻早于登记时刻时经过天数按 0 处理（效价不因时钟回拨而上升）。
 * 所有结果统一舍入到 4 位小数（HALF_UP），与持久化精度 DECIMAL(20,4) 一致。
 */
public final class PotencyCalculator {

    /**
     * 效价结果精度：4 位小数，HALF_UP。
     */
    public static final int SCALE = 4;

    private PotencyCalculator() {
    }

    /**
     * 登记时刻至计算时刻经过的 UTC 整日数（左闭右开），不为负。
     */
    public static long elapsedUtcDays(Instant registeredAt, Instant evaluatedAt) {
        LocalDate from = LocalDate.ofInstant(registeredAt, ZoneOffset.UTC);
        LocalDate to = LocalDate.ofInstant(evaluatedAt, ZoneOffset.UTC);
        long days = ChronoUnit.DAYS.between(from, to);
        return Math.max(days, 0L);
    }

    /**
     * 线性衰减后的当前效价：initial - ratePerDay × elapsedDays，舍入到 4 位小数 HALF_UP。
     */
    public static BigDecimal currentPotency(BigDecimal initial, BigDecimal ratePerDay,
                                            long elapsedDays) {
        return initial.subtract(ratePerDay.multiply(BigDecimal.valueOf(elapsedDays)))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 多父批效价加权平均：Σ(效价ᵢ × 权重ᵢ) / Σ权重ᵢ，舍入到 4 位小数 HALF_UP。
     * potencies 与 weights 等长，权重和必须为正。
     */
    public static BigDecimal weightedAverage(List<BigDecimal> potencies, List<BigDecimal> weights) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (int i = 0; i < potencies.size(); i++) {
            weightedSum = weightedSum.add(potencies.get(i).multiply(weights.get(i)));
            weightSum = weightSum.add(weights.get(i));
        }
        return weightedSum.divide(weightSum, SCALE, RoundingMode.HALF_UP);
    }
}
