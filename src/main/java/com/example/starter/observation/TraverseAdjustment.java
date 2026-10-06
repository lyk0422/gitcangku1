package com.example.starter.observation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 闭合导线闭合差与逐边修正的纯计算逻辑。
 *
 * <p>闭合差为全部边 dx、dy 各自求和；两个绝对值均不得超过 0.1 米，否则抛出 422 业务异常。
 * 逐边修正：每边 dx（dy 同理）减去总闭合差乘本边 w 除以总 w；前 n-1 边按 HALF_UP 保留 6 位小数，
 * 末边取前边修正值之和的相反数，使全部边修正值之和精确为 0（精确闭合）。
 */
public final class TraverseAdjustment {

    /**
     * 闭合差绝对值上限（米）。
     */
    public static final BigDecimal CLOSURE_LIMIT = new BigDecimal("0.1");

    /**
     * 修正值保留小数位数。
     */
    public static final int ADJUSTED_SCALE = 6;

    private static final int DIVISION_SCALE = 20;

    private TraverseAdjustment() {
    }

    /**
     * 一条边的原始位移输入。
     *
     * @param dx 位移 X 分量（米，十进制字符串）
     * @param dy 位移 Y 分量（米，十进制字符串）
     * @param w  边长权重（米，正数十进制字符串）
     */
    public record LegInput(String dx, String dy, String w) {
    }

    /**
     * 一条边的修正结果（六位小数十进制字符串）。
     *
     * @param adjustedDx 修正后位移 X 分量（米）
     * @param adjustedDy 修正后位移 Y 分量（米）
     */
    public record AdjustedLeg(String adjustedDx, String adjustedDy) {
    }

    /**
     * 整条导线的计算结果。
     *
     * @param closureDx   X 向闭合差（米）
     * @param closureDy   Y 向闭合差（米）
     * @param totalWeight 各边权重之和（米）
     * @param legs        按输入顺序的逐边修正结果
     */
    public record Adjustment(BigDecimal closureDx, BigDecimal closureDy, BigDecimal totalWeight,
                             List<AdjustedLeg> legs) {
    }

    /**
     * 计算闭合差并逐边修正；闭合差任一方向绝对值超过 0.1 米时抛出 422 业务异常。
     */
    public static Adjustment compute(List<LegInput> legs) {
        BigDecimal closureDx = BigDecimal.ZERO;
        BigDecimal closureDy = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (LegInput leg : legs) {
            closureDx = closureDx.add(new BigDecimal(leg.dx()));
            closureDy = closureDy.add(new BigDecimal(leg.dy()));
            totalWeight = totalWeight.add(new BigDecimal(leg.w()));
        }
        if (closureDx.abs().compareTo(CLOSURE_LIMIT) > 0
                || closureDy.abs().compareTo(CLOSURE_LIMIT) > 0) {
            throw ApiException.unprocessable(
                    "closure error exceeds 0.1m: closureDx=" + closureDx.toPlainString()
                            + ", closureDy=" + closureDy.toPlainString());
        }

        List<AdjustedLeg> adjusted = new ArrayList<>(legs.size());
        BigDecimal accumulatedDx = BigDecimal.ZERO;
        BigDecimal accumulatedDy = BigDecimal.ZERO;
        for (int i = 0; i < legs.size() - 1; i++) {
            LegInput leg = legs.get(i);
            BigDecimal ratio = new BigDecimal(leg.w()).divide(totalWeight, DIVISION_SCALE, RoundingMode.HALF_UP);
            BigDecimal adjustedDx = new BigDecimal(leg.dx()).subtract(closureDx.multiply(ratio))
                    .setScale(ADJUSTED_SCALE, RoundingMode.HALF_UP);
            BigDecimal adjustedDy = new BigDecimal(leg.dy()).subtract(closureDy.multiply(ratio))
                    .setScale(ADJUSTED_SCALE, RoundingMode.HALF_UP);
            adjusted.add(new AdjustedLeg(adjustedDx.toPlainString(), adjustedDy.toPlainString()));
            accumulatedDx = accumulatedDx.add(adjustedDx);
            accumulatedDy = accumulatedDy.add(adjustedDy);
        }
        // 末边取前边修正值之和的相反数：全部边修正值之和精确为 0
        adjusted.add(new AdjustedLeg(
                accumulatedDx.negate().setScale(ADJUSTED_SCALE, RoundingMode.HALF_UP).toPlainString(),
                accumulatedDy.negate().setScale(ADJUSTED_SCALE, RoundingMode.HALF_UP).toPlainString()));
        return new Adjustment(closureDx, closureDy, totalWeight, List.copyOf(adjusted));
    }
}
