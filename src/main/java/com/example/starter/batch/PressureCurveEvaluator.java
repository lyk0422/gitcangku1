package com.example.starter.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 压力曲线检验评估器：密封产品在单点检验之外，按相对时刻（秒）提交 2～10 个测量点，
 * 相邻点间压力线性变化。两道门禁任一不满足即 FAIL：
 * <ul>
 *   <li>逐段下降速率不得超过 0.2 千帕/秒：以精确交叉乘 descent*5 &lt;= dt 判定，不提前舍入速率；</li>
 *   <li>整段累计下降不得超过 1 千帕：累计下降为各下降段正下降之和，上升段不抵扣，
 *       不以首末差代替。</li>
 * </ul>
 * 节点顺序属于曲线身份：相对时刻必须严格递增，逆序提交判 400 而不是排序掩盖。
 * 大时刻差值使用 long 精确计算，压力使用 BigDecimal 精确十进制运算。
 */
public final class PressureCurveEvaluator {

    /**
     * 测量点：offsetSeconds 为相对时刻（秒，首点必须为 0，末点至少 10，严格递增）；
     * pressureKpa 为该时刻压力（千帕，非负，最多 3 位小数）。
     */
    public record Point(long offsetSeconds, BigDecimal pressureKpa) {
    }

    /**
     * 评估结果：结论 + 累计下降（千帕，精确值）+ 最大逐段下降速率（千帕/秒，展示精度 10 位）。
     */
    public record Evaluation(TestOutcome outcome, BigDecimal cumulativeDescentKpa,
                             BigDecimal maxDescentRateKpaPerSec) {
    }

    public static final int MIN_POINTS = 2;
    public static final int MAX_POINTS = 10;
    public static final long MIN_LAST_OFFSET_SECONDS = 10L;
    public static final int MAX_PRESSURE_SCALE = 3;

    /**
     * 整段累计下降上限：1 千帕（含边界）。
     */
    public static final BigDecimal MAX_TOTAL_DESCENT_KPA = new BigDecimal("1");

    /**
     * 逐段下降速率上限：0.2 千帕/秒（含边界）；判定用其倒数 5 做精确交叉乘。
     */
    public static final BigDecimal MAX_DESCENT_RATE_KPA_PER_SEC = new BigDecimal("0.2");

    private static final BigDecimal RATE_RECIPROCAL = BigDecimal.valueOf(5);
    private static final int RATE_DISPLAY_SCALE = 10;

    private PressureCurveEvaluator() {
    }

    /**
     * 曲线语法校验：任一规则不满足抛 400，调用方保证不留检验记录、不占幂等键。
     */
    public static void validateSyntax(List<Point> points) {
        if (points == null || points.size() < MIN_POINTS || points.size() > MAX_POINTS) {
            throw ApiException.badRequest("压力曲线必须包含 " + MIN_POINTS + "～" + MAX_POINTS + " 个测量点");
        }
        if (points.get(0).offsetSeconds() != 0L) {
            throw ApiException.badRequest("首点相对时刻必须为 0 秒");
        }
        if (points.get(points.size() - 1).offsetSeconds() < MIN_LAST_OFFSET_SECONDS) {
            throw ApiException.badRequest("末点相对时刻必须至少为 " + MIN_LAST_OFFSET_SECONDS + " 秒");
        }
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (point.pressureKpa() == null) {
                throw ApiException.badRequest("压力值不能为空");
            }
            if (point.pressureKpa().signum() < 0) {
                throw ApiException.badRequest("压力值不得为负: " + point.pressureKpa());
            }
            if (point.pressureKpa().stripTrailingZeros().scale() > MAX_PRESSURE_SCALE) {
                throw ApiException.badRequest("压力最多 " + MAX_PRESSURE_SCALE + " 位小数: "
                        + point.pressureKpa());
            }
            if (i > 0 && point.offsetSeconds() <= points.get(i - 1).offsetSeconds()) {
                throw ApiException.badRequest(
                        "相对时刻必须按秒严格递增，节点顺序属于曲线身份，不允许重排: "
                                + points.get(i - 1).offsetSeconds() + " -> " + point.offsetSeconds());
            }
        }
    }

    /**
     * 门禁评估（前置：已通过 {@link #validateSyntax}）。上升段与持平段不产生下降也不抵扣；
     * 速率门禁用 descent*5 &lt;= dt 精确判定，不对速率提前舍入。
     */
    public static Evaluation evaluate(List<Point> points) {
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal maxRate = BigDecimal.ZERO;
        boolean rateExceeded = false;
        for (int i = 1; i < points.size(); i++) {
            Point prev = points.get(i - 1);
            Point cur = points.get(i);
            long dt = cur.offsetSeconds() - prev.offsetSeconds();
            BigDecimal descent = prev.pressureKpa().subtract(cur.pressureKpa());
            if (descent.signum() <= 0) {
                continue;
            }
            cumulative = cumulative.add(descent);
            // descent/dt <= 0.2  <=>  descent*5 <= dt；dt 为 long，全程精确十进制比较
            if (descent.multiply(RATE_RECIPROCAL).compareTo(BigDecimal.valueOf(dt)) > 0) {
                rateExceeded = true;
            }
            BigDecimal rate = descent.divide(BigDecimal.valueOf(dt), RATE_DISPLAY_SCALE,
                    RoundingMode.HALF_UP);
            if (rate.compareTo(maxRate) > 0) {
                maxRate = rate;
            }
        }
        boolean totalExceeded = cumulative.compareTo(MAX_TOTAL_DESCENT_KPA) > 0;
        TestOutcome outcome = (rateExceeded || totalExceeded) ? TestOutcome.FAIL : TestOutcome.PASS;
        return new Evaluation(outcome, normalize(cumulative), normalize(maxRate));
    }

    /**
     * 规范化十进制展示值：去尾零，零值统一为 "0"。
     */
    private static BigDecimal normalize(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.signum() == 0 ? BigDecimal.ZERO : stripped;
    }
}
