package com.example.starter.batch;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 密封批次压力曲线纯函数评估器，不依赖 Spring/JDBC，便于单元测试。
 *
 * <p>全部门禁使用精确十进制计算，不提前舍入速率：逐段速率门禁用交叉相乘
 * （下降量 ≤ 0.2 × 秒数）判定，避免除法舍入；累计下降为各下降段正下降之和，
 * 绝不用首末压力差代替。时刻差以 long 精确计算。
 */
public final class PressureCurveEvaluator {

    /** 压力文本允许形式：非负整数或 1～3 位小数（千帕），不接受科学计数法、符号与空白。 */
    private static final Pattern PRESSURE_PATTERN = Pattern.compile("\\d+(?:\\.\\d{1,3})?");

    /** 单段最大允许下降速率：每秒 0.2 千帕。 */
    public static final BigDecimal MAX_RATE_PER_SECOND = new BigDecimal("0.2");

    /** 全部下降段累计正下降上限：1 千帕；上升段不抵消其他下降段的累计下降。 */
    public static final BigDecimal MAX_TOTAL_DECLINE = BigDecimal.ONE;

    /** 末点最小相对时刻：10 秒。 */
    public static final long MIN_LAST_SECOND = 10L;

    /** 派生展示值（不参与门禁）使用的高精度上下文。 */
    private static final MathContext DISPLAY_MATH_CONTEXT = MathContext.DECIMAL128;

    private PressureCurveEvaluator() {
    }

    /**
     * 单个曲线节点（已解析）。
     *
     * @param seq         提交顺序序号，从 1 开始；顺序属于身份，不做排序
     * @param second      相对时刻（秒）
     * @param pressureRaw 压力原文（千帕），文本原样保留
     * @param pressure    按值解析的压力
     */
    public record Point(int seq, long second, String pressureRaw, BigDecimal pressure) {
    }

    /**
     * 单段评估结果（相邻两节点线性变化）。
     *
     * @param seq        段序号，从 1 开始
     * @param fromSecond 起点秒
     * @param toSecond   终点秒（秒数差为 long 精确差值）
     * @param decline    正下降量（千帕）：起点压力高于终点时为正差值，否则为 0；精确十进制
     * @param rate       逐段下降速率（千帕/秒），仅下降段为正；高精度派生展示值，门禁不使用它
     * @param rateExceeded 该段下降速率是否超过每秒 0.2 千帕
     */
    public record Segment(int seq, long fromSecond, long toSecond, BigDecimal decline,
                          BigDecimal rate, boolean rateExceeded) {
    }

    /**
     * 曲线评估结果。
     *
     * @param points              按提交顺序解析的节点
     * @param segments            逐段下降信息
     * @param totalDecline        各段正下降之和（千帕），精确十进制
     * @param maxSegmentRate      下降段中的最大逐段速率（千帕/秒）；无下降段时为 0
     * @param passed              true 表示两道门禁均满足
     * @param rejectReason        FAIL 时的中文原因，PASS 时为 null
     */
    public record Evaluation(List<Point> points, List<Segment> segments, BigDecimal totalDecline,
                             BigDecimal maxSegmentRate, boolean passed, String rejectReason) {
    }

    /**
     * 校验并评估曲线。输入非法抛 {@link ApiException} 400（不留任何检验记录）；
     * 输入合法但门禁不满足返回 FAIL 评估结果（调用方持久化 FAIL 并使批次 REJECTED）。
     */
    public static Evaluation evaluate(List<Point> rawPoints) {
        List<Point> points = validate(rawPoints);
        List<Segment> segments = new ArrayList<>(points.size() - 1);
        BigDecimal totalDecline = BigDecimal.ZERO;
        BigDecimal maxRate = BigDecimal.ZERO;
        List<Integer> exceededSegs = new ArrayList<>();

        for (int i = 1; i < points.size(); i++) {
            Point prev = points.get(i - 1);
            Point cur = points.get(i);
            long dt = cur.second() - prev.second();
            BigDecimal diff = prev.pressure().subtract(cur.pressure());
            BigDecimal decline = diff.compareTo(BigDecimal.ZERO) > 0 ? diff : BigDecimal.ZERO;
            BigDecimal rate = BigDecimal.ZERO;
            boolean exceeded = false;
            if (decline.compareTo(BigDecimal.ZERO) > 0) {
                // 门禁交叉相乘：decline / dt <= 0.2 等价于 decline <= 0.2 * dt，全程精确不舍入
                BigDecimal allowed = MAX_RATE_PER_SECOND.multiply(BigDecimal.valueOf(dt));
                exceeded = decline.compareTo(allowed) > 0;
                // 展示/冻结用速率：高精度除法后去掉尾随零；门禁本身使用上面的交叉相乘，不依赖此值
                rate = decline.divide(BigDecimal.valueOf(dt), DISPLAY_MATH_CONTEXT)
                        .stripTrailingZeros();
                if (rate.compareTo(maxRate) > 0) {
                    maxRate = rate.stripTrailingZeros();
                }
                totalDecline = totalDecline.add(decline);
            }
            if (exceeded) {
                exceededSegs.add(i);
            }
            segments.add(new Segment(i, prev.second(), cur.second(), decline, rate, exceeded));
        }

        boolean totalExceeded = totalDecline.compareTo(MAX_TOTAL_DECLINE) > 0;
        boolean passed = exceededSegs.isEmpty() && !totalExceeded;
        String reason = null;
        if (!passed) {
            List<String> reasons = new ArrayList<>(2);
            if (!exceededSegs.isEmpty()) {
                reasons.add("第 " + exceededSegs + " 段下降速率超过每秒 0.2 千帕");
            }
            if (totalExceeded) {
                reasons.add("累计正下降 " + totalDecline + " 千帕超过 1 千帕");
            }
            reason = String.join("；", reasons);
        }
        return new Evaluation(points, segments, totalDecline.stripTrailingZeros(),
                maxRate, passed, reason);
    }

    /**
     * 解析原始（秒, 压力文本）对：非法结构抛 400。
     */
    public static List<Point> validate(List<Point> points) {
        if (points == null || points.size() < 2 || points.size() > 10) {
            throw ApiException.badRequest("points 必须包含 2～10 个节点");
        }
        List<Point> parsed = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            if (p == null || p.pressureRaw() == null || p.pressureRaw().isBlank()) {
                throw ApiException.badRequest("第 " + (i + 1) + " 个节点缺少秒数或压力值");
            }
            long second = p.second();
            if (second < 0) {
                throw ApiException.badRequest("第 " + (i + 1) + " 个节点相对时刻不能为负: " + second);
            }
            String raw = p.pressureRaw();
            if (!PRESSURE_PATTERN.matcher(raw).matches()) {
                throw ApiException.badRequest(
                        "第 " + (i + 1) + " 个节点压力值非法（须为非负数字、最多 3 位小数、单位千帕）: " + raw);
            }
            parsed.add(new Point(i + 1, second, raw, new BigDecimal(raw)));
        }
        if (parsed.get(0).second() != 0L) {
            throw ApiException.badRequest("首个节点的相对秒数必须为 0，实际为 " + parsed.get(0).second());
        }
        for (int i = 1; i < parsed.size(); i++) {
            if (parsed.get(i).second() <= parsed.get(i - 1).second()) {
                throw ApiException.badRequest(
                        "第 " + (i + 1) + " 个节点相对秒数必须严格递增（节点顺序按提交身份不重排），第 "
                                + i + " 点秒数 " + parsed.get(i - 1).second() + "，第 " + (i + 1)
                                + " 点秒数 " + parsed.get(i).second());
            }
        }
        if (parsed.get(parsed.size() - 1).second() < MIN_LAST_SECOND) {
            throw ApiException.badRequest(
                    "末点相对秒数至少为 10，实际为 " + parsed.get(parsed.size() - 1).second());
        }
        return parsed;
    }

    /**
     * 便捷构造待解析的原始节点（序号与数值由 {@link #validate(List)} 填充）。
     */
    public static Point raw(long second, String pressureRaw) {
        return new Point(0, second, pressureRaw, null);
    }
}
