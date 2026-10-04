package com.example.starter.water;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 流量曲线数学工具。节点时刻为 UTC 整分钟（epoch 分钟），流量单位立方米每分钟；
 * 相邻节点线性变化，梯形积分等于申请水量。
 *
 * <p>叠加判定在全部曲线节点的并集时刻上逐点精确求值：每条曲线在自身时间范围外贡献 0，
 * 范围内按线性插值取值。求和与比较使用精确有理数运算，判断前不舍入。</p>
 */
public final class FlowCurve {

    /** 曲线节点：minute 为 UTC epoch 分钟，flow 单位立方米每分钟（非负，最多 3 位小数）。 */
    public record Node(long minute, BigDecimal flow) {
    }

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private FlowCurve() {
    }

    /** 梯形积分（单位立方米）：相邻节点线性变化下的精确水量，BigDecimal 精确计算。 */
    public static BigDecimal integrate(List<Node> nodes) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i + 1 < nodes.size(); i++) {
            Node a = nodes.get(i);
            Node b = nodes.get(i + 1);
            BigDecimal minutes = BigDecimal.valueOf(b.minute() - a.minute());
            // 除以 2 在十进制下精确，不产生舍入
            sum = sum.add(a.flow().add(b.flow()).multiply(minutes).divide(TWO));
        }
        return sum;
    }

    /**
     * 校验多条曲线叠加后：所有并集节点处总流量不超过 maxFlow，
     * 所有相邻并集时刻区间上总斜率绝对值不超过 maxRamp。越限抛出 422。
     */
    public static void assertWithinLimits(List<List<Node>> curves, BigDecimal maxFlow, BigDecimal maxRamp) {
        TreeSet<Long> union = new TreeSet<>();
        for (List<Node> curve : curves) {
            for (Node node : curve) {
                union.add(node.minute());
            }
        }
        List<Long> times = new ArrayList<>(union);
        for (long t : times) {
            Rat total = new Rat();
            for (List<Node> curve : curves) {
                addInterpolated(total, curve, t);
            }
            if (total.compareTo(maxFlow) > 0) {
                throw ApiException.unprocessable("PUMP_FLOW_EXCEEDED",
                        "叠加流量在分钟 " + t + " 处超过泵站最大流量 " + maxFlow.toPlainString());
            }
        }
        for (int i = 0; i + 1 < times.size(); i++) {
            long a = times.get(i);
            long b = times.get(i + 1);
            Rat slope = new Rat();
            for (List<Node> curve : curves) {
                addSlope(slope, curve, a, b);
            }
            if (slope.absGreaterThan(maxRamp)) {
                throw ApiException.unprocessable("PUMP_RAMP_EXCEEDED",
                        "叠加斜率在分钟 [" + a + ", " + b + "] 上超过泵站每分钟最大变化量 "
                                + maxRamp.toPlainString());
            }
        }
    }

    /** 将曲线在时刻 t 的插值流量（精确有理数）累加到 total；t 在曲线范围外时贡献 0。 */
    private static void addInterpolated(Rat total, List<Node> curve, long t) {
        if (t < curve.get(0).minute() || t > curve.get(curve.size() - 1).minute()) {
            return;
        }
        int i = segmentOf(curve, t);
        Node lo = curve.get(i);
        Node hi = curve.get(i + 1);
        // f(t) = [f_lo * (t_hi - t) + f_hi * (t - t_lo)] / (t_hi - t_lo)
        BigDecimal num = lo.flow().multiply(BigDecimal.valueOf(hi.minute() - t))
                .add(hi.flow().multiply(BigDecimal.valueOf(t - lo.minute())));
        total.add(num, BigDecimal.valueOf(hi.minute() - lo.minute()));
    }

    /** 将曲线在区间 [a, b] 上的斜率（精确有理数）累加到 total；区间在曲线范围外时贡献 0。 */
    private static void addSlope(Rat total, List<Node> curve, long a, long b) {
        if (b <= curve.get(0).minute() || a >= curve.get(curve.size() - 1).minute()) {
            return;
        }
        int i = segmentOf(curve, a);
        Node lo = curve.get(i);
        Node hi = curve.get(i + 1);
        // a、b 均为并集时刻，[a, b] 必落在单个曲线段内，斜率为该段常数
        total.add(hi.flow().subtract(lo.flow()), BigDecimal.valueOf(hi.minute() - lo.minute()));
    }

    /** 二分查找 t 所在曲线段下标 i，满足 nodes[i].minute <= t <= nodes[i+1].minute。 */
    private static int segmentOf(List<Node> curve, long t) {
        int lo = 0;
        int hi = curve.size() - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (curve.get(mid).minute() <= t) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /**
     * 精确有理数累加器（num/den，den 恒正）。用于判断前不舍入的求和与比较：
     * 分母为曲线段分钟数，乘积增长有限，BigDecimal 任意精度保证精确。
     */
    private static final class Rat {
        private BigDecimal num = BigDecimal.ZERO;
        private BigDecimal den = BigDecimal.ONE;

        void add(BigDecimal n, BigDecimal d) {
            num = num.multiply(d).add(n.multiply(den));
            den = den.multiply(d);
        }

        /** 与 limit 比较：this <=> limit。 */
        int compareTo(BigDecimal limit) {
            return num.compareTo(limit.multiply(den));
        }

        /** |this| > limit（limit 为正）。 */
        boolean absGreaterThan(BigDecimal limit) {
            BigDecimal bound = limit.multiply(den);
            return num.compareTo(bound) > 0 || num.compareTo(bound.negate()) < 0;
        }
    }
}