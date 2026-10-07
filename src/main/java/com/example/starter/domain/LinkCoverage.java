package com.example.starter.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 沿航段参数区间的合成通信覆盖计算。
 *
 * <p>每段在位置刻度 0 至 10000 上登记覆盖闭区间；先合并并集，未覆盖刻度长度
 * 乘段时长除以 10000 得到失联时间。跨相邻段的尾部缺口与头部缺口连成同一段
 * 连续失联。全程使用精确有理数（分子为刻度长度 × 段时长，分母固定 10000），
 * 不提前取整；任一连续失联严格超过 5000 毫秒即链路阻断，等于允许。</p>
 */
public final class LinkCoverage {

    /** 位置刻度总长（0 至 10000 的闭区间长度）。 */
    public static final int SCALE = 10000;
    /** 允许的最大连续失联时长（毫秒）：严格超过即阻断，等于允许。 */
    public static final long MAX_CONTINUOUS_LOSS_MS = 5000L;
    /** 失联时长有理数分母：失联毫秒 = 未覆盖刻度长度 × 段时长 / SCALE。 */
    public static final long LOSS_DENOMINATOR = SCALE;
    /** 阻断阈值分子：lossNumerator 严格大于该值即阻断。 */
    public static final long BLOCK_THRESHOLD_NUMERATOR = MAX_CONTINUOUS_LOSS_MS * LOSS_DENOMINATOR;

    private LinkCoverage() {
    }

    /**
     * 一段连续失联（可跨相邻段：前段尾部缺口与后段头部缺口连成同一段）。
     *
     * @param fromSegment   起始航段序号（含，从 0 开始）
     * @param toSegment     结束航段序号（含）
     * @param lossNumerator 失联时长分子（精确有理数，分母固定为 LOSS_DENOMINATOR）
     */
    public record LinkGap(int fromSegment, int toSegment, long lossNumerator) {

        /** 精确失联毫秒（分母 10000 必可表示为有限小数），不四舍五入。 */
        public String lossMs() {
            return BigDecimal.valueOf(lossNumerator)
                    .divide(BigDecimal.valueOf(LOSS_DENOMINATOR))
                    .stripTrailingZeros().toPlainString();
        }
    }

    /**
     * 合并单段覆盖闭区间并集：重叠或端点相接的区间合并为一条；
     * 端点相接处的零时长缺口不计，重叠部分不双计。
     *
     * @param intervals 该段登记的覆盖闭区间（可重叠，可为空）
     * @return 按起始刻度升序、互不重叠的合并并集
     */
    public static List<CoverageInterval> merge(List<CoverageInterval> intervals) {
        List<CoverageInterval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparingInt(CoverageInterval::start)
                .thenComparingInt(CoverageInterval::end));
        List<CoverageInterval> merged = new ArrayList<>(sorted.size());
        for (CoverageInterval interval : sorted) {
            if (!merged.isEmpty()) {
                CoverageInterval last = merged.get(merged.size() - 1);
                // 重叠或端点相接（闭区间共享端点，零时长缺口不计）时并入上一条
                if (interval.start() <= last.end()) {
                    if (interval.end() > last.end()) {
                        merged.set(merged.size() - 1,
                                new CoverageInterval(last.start(), interval.end()));
                    }
                    continue;
                }
            }
            merged.add(interval);
        }
        return merged;
    }

    /**
     * 计算全部连续失联段（含跨相邻段连接），按出现顺序返回。
     *
     * @param segments 各段通信档案（段序与航线点序一致）
     * @return 连续失联段列表；全程覆盖时为空列表
     */
    public static List<LinkGap> continuousLosses(List<SegmentProfile> segments) {
        List<LinkGap> joined = new ArrayList<>();
        // 上一段最后一个缺口是否触及段尾（只有触及段尾才能与下一段头部缺口相连）
        boolean lastTouchesTail = false;
        for (int i = 0; i < segments.size(); i++) {
            SegmentProfile segment = segments.get(i);
            List<CoverageInterval> union = merge(segment.coverage());
            // 依次产出该段内全部未覆盖刻度区间 [cursor, nextStart)
            int cursor = 0;
            for (CoverageInterval covered : union) {
                if (covered.start() > cursor) {
                    lastTouchesTail = appendGap(joined, i, cursor, covered.start(),
                            segment.durationMs(), lastTouchesTail);
                }
                cursor = covered.end();
            }
            if (cursor < SCALE) {
                lastTouchesTail = appendGap(joined, i, cursor, SCALE,
                        segment.durationMs(), lastTouchesTail);
            } else {
                lastTouchesTail = false;
            }
        }
        return joined;
    }

    /**
     * 追加一个段内未覆盖区间 [startTick, endTick) 到连续失联列表；
     * 若它位于段头且上一段尾部缺口未闭合，则并入同一段连续失联（跨段连接）。
     *
     * @return 追加后列表末尾缺口是否触及段尾
     */
    private static boolean appendGap(List<LinkGap> joined, int segment, int startTick,
                                     int endTick, int durationMs, boolean lastTouchesTail) {
        long numerator = (long) (endTick - startTick) * durationMs;
        boolean touchesHead = startTick == 0;
        boolean touchesTail = endTick == SCALE;
        if (touchesHead && lastTouchesTail && !joined.isEmpty()) {
            LinkGap previous = joined.get(joined.size() - 1);
            joined.set(joined.size() - 1, new LinkGap(previous.fromSegment(), segment,
                    previous.lossNumerator() + numerator));
        } else {
            joined.add(new LinkGap(segment, segment, numerator));
        }
        return touchesTail;
    }

    /**
     * 是否存在严格超过 5000 毫秒的连续失联（等于 5000 允许）。
     */
    public static boolean anyBlocked(List<LinkGap> gaps) {
        for (LinkGap gap : gaps) {
            // 精确有理数比较：失联毫秒 > 5000 当且仅当分子 > 5000 × 10000
            if (gap.lossNumerator() > BLOCK_THRESHOLD_NUMERATOR) {
                return true;
            }
        }
        return false;
    }
}
