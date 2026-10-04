package com.example.starter.playout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 频道滚动音频能量预算：任意连续 60 秒能量积分（能量密度 x 实际播放秒数）不得超过 300。
 *
 * <p>只评估正常已发布编排的片段；空档与保底素材密度视为 0，紧急插播不计入本预算。
 * 全部计算使用 BigDecimal 精确小数，不预先舍入；恰好等于 300 合法。</p>
 */
public final class EnergyBudget {

    /** 滚动窗口长度，毫秒（60 秒）。 */
    public static final long WINDOW_MS = 60_000L;

    /** 预算上限：任意连续 60 秒能量积分不超过 300。 */
    public static final BigDecimal LIMIT = new BigDecimal("300");

    private static final BigDecimal MS_PER_SECOND = BigDecimal.valueOf(1000);

    private EnergyBudget() {
    }

    /** 待评估片段；energyDensity 为发布时冻结的素材能量密度（未登记按 0）。 */
    public record Segment(String segmentId, String assetId, long startMs, long endMs,
                          BigDecimal energyDensity) {
    }

    /** 单个片段对窗口的能量贡献。 */
    public record Contribution(String segmentId, String assetId, BigDecimal energyDensity,
                               long overlapMs, BigDecimal energy) {
    }

    /** 最大滚动窗口评估结果；windowStartMs/windowEndMs 为 null 表示无任何片段。 */
    public record Evaluation(Long windowStartMs, Long windowEndMs, BigDecimal maxEnergy,
                             List<Contribution> contributions) {
        /** 是否超出预算（严格大于 300 才拒绝，恰好 300 合法）。 */
        public boolean exceeds() {
            return maxEnergy.compareTo(LIMIT) > 0;
        }
    }

    /**
     * 评估全部滚动 60 秒窗口的最大能量。片段密度分段常值，能量关于窗口起点分段线性，
     * 最大值只可能出现在窗口起点对齐某片段起点、或窗口终点对齐某片段终点
     * （即起点 = 片段终点 - 60 秒）处，与整分钟桶无关。
     */
    public static Evaluation evaluate(List<Segment> segments) {
        List<Long> candidates = new ArrayList<>();
        for (Segment segment : segments) {
            candidates.add(segment.startMs());
            candidates.add(segment.endMs() - WINDOW_MS);
        }
        Evaluation best = null;
        for (long startMs : candidates) {
            Evaluation current = evaluateWindow(segments, startMs);
            if (best == null || current.maxEnergy().compareTo(best.maxEnergy()) > 0) {
                best = current;
            }
        }
        if (best == null) {
            return new Evaluation(null, null, BigDecimal.ZERO, List.of());
        }
        return best;
    }

    /** 计算窗口 [startMs, startMs + 60s) 的能量积分与各片段贡献。 */
    public static Evaluation evaluateWindow(List<Segment> segments, long startMs) {
        long endMs = startMs + WINDOW_MS;
        BigDecimal total = BigDecimal.ZERO;
        List<Contribution> contributions = new ArrayList<>();
        for (Segment segment : segments) {
            long overlapMs = Math.min(segment.endMs(), endMs) - Math.max(segment.startMs(), startMs);
            if (overlapMs <= 0) {
                continue;
            }
            BigDecimal energy = segment.energyDensity()
                    .multiply(BigDecimal.valueOf(overlapMs))
                    .divide(MS_PER_SECOND, 6, RoundingMode.UNNECESSARY);
            total = total.add(energy);
            contributions.add(new Contribution(segment.segmentId(), segment.assetId(),
                    segment.energyDensity(), overlapMs, energy));
        }
        return new Evaluation(startMs, endMs, total, contributions);
    }
}