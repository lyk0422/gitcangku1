package com.example.starter.race.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 追逐赛最早交会时刻计算：合成直线进度模型。
 *
 * <p>每人轨迹为隐含起点 (0,0) 加采样点的分段线性折线，位置=起始距离+累计前进量；
 * 在双方轨迹的公共时间区间 [0, min(各自最后采样时刻)] 内（含端点，不外推），
 * 用精确有理数运算找“追赶者位置-被追者位置”最早达到 0 的时刻，不做任何取整。
 */
public final class PursuitCatchCalculator {

    private PursuitCatchCalculator() {
    }

    /**
     * 计算最早交会时刻。
     *
     * @param chaserStartMm 追赶者起始距离（毫米），必须小于被追者起始距离
     * @param chasedStartMm 被追者起始距离（毫米）
     * @param chaserSamples 追赶者采样点（时刻严格递增、距离不减，1~10个）
     * @param chasedSamples 被追者采样点（同上）
     * @return 最早交会时刻的精确有理数；公共区间内未交会为 empty
     */
    public static Optional<CatchTime> earliestCatch(
            long chaserStartMm,
            long chasedStartMm,
            List<TrajectoryPoint> chaserSamples,
            List<TrajectoryPoint> chasedSamples) {
        List<long[]> chaserKnots = knots(chaserStartMm, chaserSamples);
        List<long[]> chasedKnots = knots(chasedStartMm, chasedSamples);
        long commonEnd = Math.min(
                chaserSamples.getLast().timeMs(), chasedSamples.getLast().timeMs());
        // 双方折点时刻的并集（截断到公共区间），保证每一小段内两条轨迹都是线性的。
        TreeSet<Long> cuts = new TreeSet<>();
        cuts.add(0L);
        cuts.add(commonEnd);
        for (long[] knot : chaserKnots) {
            if (knot[0] > 0 && knot[0] < commonEnd) {
                cuts.add(knot[0]);
            }
        }
        for (long[] knot : chasedKnots) {
            if (knot[0] > 0 && knot[0] < commonEnd) {
                cuts.add(knot[0]);
            }
        }
        Long previous = null;
        for (Long cut : cuts) {
            if (previous != null) {
                Optional<CatchTime> hit =
                        scanSegment(chaserKnots, chasedKnots, previous, cut);
                if (hit.isPresent()) {
                    return hit;
                }
            }
            previous = cut;
        }
        return Optional.empty();
    }

    /** 扫描小段 [t0, t1]：位置差为线性函数，精确求零点（含端点）。 */
    private static Optional<CatchTime> scanSegment(
            List<long[]> chaserKnots, List<long[]> chasedKnots, long t0, long t1) {
        Frac diff0 = positionAt(chaserKnots, t0).sub(positionAt(chasedKnots, t0));
        Frac diff1 = positionAt(chaserKnots, t1).sub(positionAt(chasedKnots, t1));
        if (diff0.signum() == 0) {
            return Optional.of(new CatchTime(t0, 1L));
        }
        if (diff0.signum() < 0 && diff1.signum() >= 0) {
            // t* = t0 + (t1 - t0) * (-diff0) / (diff1 - diff0)，线性插值精确解。
            Frac ratio = diff0.negate().div(diff1.sub(diff0));
            Frac tStar = Frac.of(t0).add(Frac.of(t1 - t0).mul(ratio));
            return Optional.of(new CatchTime(
                    tStar.num.longValueExact(), tStar.den.longValueExact()));
        }
        return Optional.empty();
    }

    /** 折线轨迹在时刻 t 的位置（精确有理数，单位毫米）。 */
    private static Frac positionAt(List<long[]> knots, long t) {
        for (int i = 0; i < knots.size() - 1; i++) {
            long[] a = knots.get(i);
            long[] b = knots.get(i + 1);
            if (t >= a[0] && t <= b[0]) {
                if (t == a[0]) {
                    return Frac.of(a[1]);
                }
                if (t == b[0]) {
                    return Frac.of(b[1]);
                }
                // pos = pa + (pb - pa) * (t - ta) / (tb - ta)
                Frac ratio = Frac.of(t - a[0]).div(Frac.of(b[0] - a[0]));
                return Frac.of(a[1]).add(Frac.of(b[1] - a[1]).mul(ratio));
            }
        }
        throw new IllegalArgumentException("时刻超出轨迹区间: " + t);
    }

    /** 由起始距离与采样点构造位置折点序列：[时刻毫秒, 位置毫米]，含 (0, 起始距离)。 */
    private static List<long[]> knots(long startMm, List<TrajectoryPoint> samples) {
        List<long[]> knots = new ArrayList<>(samples.size() + 1);
        knots.add(new long[]{0L, startMm});
        for (TrajectoryPoint sample : samples) {
            knots.add(new long[]{sample.timeMs(), startMm + sample.distanceMm()});
        }
        return knots;
    }

    /** 已约分的精确有理数（分母恒正）。 */
    private record Frac(BigInteger num, BigInteger den) {

        Frac {
            if (den.signum() == 0) {
                throw new ArithmeticException("分母为0");
            }
            if (den.signum() < 0) {
                num = num.negate();
                den = den.negate();
            }
            BigInteger gcd = num.gcd(den);
            if (!gcd.equals(BigInteger.ONE)) {
                num = num.divide(gcd);
                den = den.divide(gcd);
            }
        }

        static Frac of(long value) {
            return new Frac(BigInteger.valueOf(value), BigInteger.ONE);
        }

        Frac add(Frac other) {
            return new Frac(
                    num.multiply(other.den).add(other.num.multiply(den)),
                    den.multiply(other.den));
        }

        Frac sub(Frac other) {
            return add(other.negate());
        }

        Frac negate() {
            return new Frac(num.negate(), den);
        }

        Frac mul(Frac other) {
            return new Frac(num.multiply(other.num), den.multiply(other.den));
        }

        Frac div(Frac other) {
            if (other.num.signum() == 0) {
                throw new ArithmeticException("除以0");
            }
            return new Frac(num.multiply(other.den), den.multiply(other.num));
        }

        int signum() {
            return num.signum();
        }
    }
}
