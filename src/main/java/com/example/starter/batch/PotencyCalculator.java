package com.example.starter.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 效价模型纯计算核心（无 IO、无状态，可独立单测）。
 *
 * <p>统一约定（全部时间 UTC，区间左闭右开）：
 * <ul>
 *   <li>效价/阈值/差额/衰减量保留 6 位小数（HALF_UP）；经过天数保留 6 位小数；权重保留 10 位。</li>
 *   <li>线性衰减：当前效价 = 初始效价 - 日衰减率 × 经过UTC日；经过天数按纳秒连续计时，非整日折算。</li>
 *   <li>合批权重 = 父批数量 / 合批总量；末位权重取 1 - 其余权重之和以保证守恒。</li>
 * </ul>
 */
public final class PotencyCalculator {

    /** 效价值舍入位数。 */
    public static final int POTENCY_SCALE = 6;
    /** 权重舍入位数。 */
    public static final int WEIGHT_SCALE = 10;
    private static final int DAYS_SCALE = 6;
    private static final long NANOS_PER_DAY = Duration.ofDays(1).toNanos();

    private PotencyCalculator() {
    }

    /**
     * 效价模型版本快照。
     */
    public record Model(int version, BigDecimal initialPotency, BigDecimal minPotency,
                        BigDecimal decayRate, Instant effectiveAt) {
    }

    /**
     * 某时刻的效价计算结果（舍入后的稳定值）。
     */
    public record Computed(Instant evaluatedAt, BigDecimal elapsedDays, BigDecimal decayedPotency,
                           BigDecimal currentPotency, BigDecimal thresholdPotency,
                           BigDecimal difference, boolean pass) {
    }

    /**
     * 父批合批贡献输入。
     */
    public record Contribution(String batchKey, BigDecimal quantity, BigDecimal currentPotency,
                               BigDecimal minPotency) {
    }

    /**
     * 加权合批结果。
     *
     * @param weightedPotency 新批次初始效价（数量加权，6 位小数）
     * @param weights         与贡献同序的实际权重（10 位小数，守恒为 1）
     * @param strictestMin    父批要求阈值中的最大值（最严格阈值），作为子批最低放行效价
     * @param weightedDecay   父批衰减率的数量加权值，作为子批日衰减率
     */
    public record Weighted(BigDecimal weightedPotency, List<BigDecimal> weights,
                           BigDecimal strictestMin, BigDecimal weightedDecay) {
    }

    /** 效价值舍入。 */
    public static BigDecimal potency(BigDecimal v) {
        return v.setScale(POTENCY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 经过 UTC 日：(at - effectiveAt) 按纳秒连续折算，早于基准时刻返回负值。
     */
    public static BigDecimal elapsedDays(Instant effectiveAt, Instant at) {
        BigDecimal nanos = BigDecimal.valueOf(Duration.between(effectiveAt, at).toNanos());
        return nanos.divide(BigDecimal.valueOf(NANOS_PER_DAY), DAYS_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算模型在 at 时刻的效价核验结果。当前效价低于阈值（严格小于）即不合格。
     */
    public static Computed evaluate(Model model, Instant at) {
        BigDecimal days = elapsedDays(model.effectiveAt(), at);
        BigDecimal decayed = potency(model.decayRate().multiply(days));
        BigDecimal current = potency(model.initialPotency().subtract(decayed));
        BigDecimal threshold = potency(model.minPotency());
        BigDecimal difference = potency(current.subtract(threshold));
        boolean pass = current.compareTo(threshold) >= 0;
        return new Computed(at, days, decayed, current, threshold, difference, pass);
    }

    /**
     * 以数量为权对父批操作时刻效价加权，计算合批新批次初始效价与子批模型参数。
     * 调用前必须保证每个数量为正且总量为正。
     */
    public static Weighted weight(List<Contribution> contributions, List<BigDecimal> parentDecayRates) {
        if (contributions.size() != parentDecayRates.size()) {
            throw new IllegalArgumentException("贡献与衰减率数量不一致");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Contribution c : contributions) {
            total = total.add(c.quantity());
        }
        if (total.signum() <= 0) {
            throw new IllegalArgumentException("合批总量必须为正");
        }
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal decaySum = BigDecimal.ZERO;
        BigDecimal strictestMin = null;
        java.util.List<BigDecimal> weights = new java.util.ArrayList<>(contributions.size());
        BigDecimal weightSum = BigDecimal.ZERO;
        for (int i = 0; i < contributions.size(); i++) {
            Contribution c = contributions.get(i);
            BigDecimal w = c.quantity().divide(total, WEIGHT_SCALE, RoundingMode.HALF_UP);
            weights.add(w);
            weightSum = weightSum.add(w);
            weightedSum = weightedSum.add(c.currentPotency().multiply(w));
            decaySum = decaySum.add(parentDecayRates.get(i).multiply(w));
            if (strictestMin == null || c.minPotency().compareTo(strictestMin) > 0) {
                strictestMin = c.minPotency();
            }
        }
        // 舍入误差交给末位权重吸收，保证权重严格守恒为 1
        BigDecimal last = weights.get(weights.size() - 1)
                .add(BigDecimal.ONE.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP).subtract(weightSum));
        weights.set(weights.size() - 1, last);
        return new Weighted(potency(weightedSum), List.copyOf(weights),
                potency(strictestMin), potency(decaySum));
    }
}
