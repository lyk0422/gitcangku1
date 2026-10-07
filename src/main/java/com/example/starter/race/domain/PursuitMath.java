package com.example.starter.race.domain;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 追逐赛交会判定：只处理合成直线进度，不模拟赛道。
 *
 * <p>位置（毫米）= 起始距离 + 累计前进量；采样点之间线性插值。
 * 双方轨迹的公共区间为 [0, min(各自末点时刻)]，只在公共区间内求
 * 最早“追赶者位置 - 被追者位置 = 0”的时刻；禁止向公共区间之外外推，
 * 结果以精确有理数表示，不取整。
 */
public final class PursuitMath {

    private PursuitMath() {
    }

    /**
     * 轨迹采样点。
     *
     * @param elapsedMs  从起跑起的累计毫秒（非负整数，同一人严格递增，首点固定0）
     * @param progressMm 累计前进毫米（非负整数，同一人单调不减，首点固定0）
     */
    public record SamplePoint(long elapsedMs, long progressMm) {
    }

    /**
     * 在双方轨迹公共区间内求最早交会时刻。
     *
     * @param pursuerSamples 追赶者采样点（已校验：首点(0,0)、时刻严格递增、距离不减）
     * @param pursuerStartMm 追赶者起始距离（毫米）
     * @param pursuedSamples 被追者采样点
     * @param pursuedStartMm 被追者起始距离（毫米）
     * @return 最早交会时刻（精确有理数毫秒）；公共区间内位置差始终为负时返回 empty
     */
    public static Optional<Rational> earliestCatchTime(
            List<SamplePoint> pursuerSamples,
            long pursuerStartMm,
            List<SamplePoint> pursuedSamples,
            long pursuedStartMm) {
        long horizon = Math.min(
                pursuerSamples.getLast().elapsedMs(),
                pursuedSamples.getLast().elapsedMs());
        // 公共区间内的全部折点（双方采样时刻的并集），窗口内两条轨迹均为线性。
        TreeSet<Long> breakpoints = new TreeSet<>();
        breakpoints.add(0L);
        breakpoints.add(horizon);
        for (SamplePoint point : pursuerSamples) {
            if (point.elapsedMs() > 0 && point.elapsedMs() < horizon) {
                breakpoints.add(point.elapsedMs());
            }
        }
        for (SamplePoint point : pursuedSamples) {
            if (point.elapsedMs() > 0 && point.elapsedMs() < horizon) {
                breakpoints.add(point.elapsedMs());
            }
        }
        List<Long> times = List.copyOf(breakpoints);
        for (int i = 0; i + 1 < times.size(); i++) {
            long windowStart = times.get(i);
            long windowEnd = times.get(i + 1);
            Rational diffStart = positionDiff(
                    pursuerSamples, pursuerStartMm, pursuedSamples, pursuedStartMm, windowStart);
            if (!diffStart.isNegative()) {
                // 位置差已在窗口起点达到0（含 t=0 的防御性处理）。
                return Optional.of(Rational.of(windowStart));
            }
            Rational diffEnd = positionDiff(
                    pursuerSamples, pursuerStartMm, pursuedSamples, pursuedStartMm, windowEnd);
            if (!diffEnd.isNegative()) {
                // 窗口内位置差从负升到非负：线性方程 diff(t)=0 的精确解
                // t = windowStart - diffStart * (windowEnd - windowStart) / (diffEnd - diffStart)。
                Rational span = Rational.of(windowEnd - windowStart);
                Rational slope = diffEnd.subtract(diffStart).divide(span);
                Rational catchTime = Rational.of(windowStart).subtract(diffStart.divide(slope));
                return Optional.of(catchTime);
            }
        }
        return Optional.empty();
    }

    /** 计算给定时刻“追赶者位置 - 被追者位置”的精确差值（毫米）。 */
    private static Rational positionDiff(
            List<SamplePoint> pursuerSamples,
            long pursuerStartMm,
            List<SamplePoint> pursuedSamples,
            long pursuedStartMm,
            long elapsedMs) {
        Rational pursuerPosition = positionAt(pursuerSamples, elapsedMs)
                .add(Rational.of(pursuerStartMm));
        Rational pursuedPosition = positionAt(pursuedSamples, elapsedMs)
                .add(Rational.of(pursuedStartMm));
        return pursuerPosition.subtract(pursuedPosition);
    }

    /**
     * 轨迹在指定时刻的累计前进量（毫米，精确有理数）。
     * 仅在 [0, 末点时刻] 内有定义；采样点之间线性插值，单点轨迹仅在 t=0 有定义。
     */
    static Rational positionAt(List<SamplePoint> samples, long elapsedMs) {
        if (samples.size() == 1) {
            if (elapsedMs != 0) {
                throw new IllegalArgumentException("单点轨迹仅在 t=0 有定义: " + elapsedMs);
            }
            return Rational.of(samples.getFirst().progressMm());
        }
        for (int i = 0; i + 1 < samples.size(); i++) {
            SamplePoint from = samples.get(i);
            SamplePoint to = samples.get(i + 1);
            if (elapsedMs >= from.elapsedMs() && elapsedMs <= to.elapsedMs()) {
                BigInteger span = BigInteger.valueOf(to.elapsedMs() - from.elapsedMs());
                BigInteger progressed = BigInteger.valueOf(to.progressMm() - from.progressMm())
                        .multiply(BigInteger.valueOf(elapsedMs - from.elapsedMs()));
                return Rational.of(
                        BigInteger.valueOf(from.progressMm()).multiply(span).add(progressed),
                        span);
            }
        }
        throw new IllegalArgumentException("时刻超出轨迹定义区间: " + elapsedMs);
    }
}
