package com.example.starter.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 合成通信覆盖审查：合并各航段覆盖并集，计算连续失联区间。
 *
 * <p>输入须已通过校验（区间闭合于 [0,10000] 且 from &lt;= to，时长为正整数）。
 * 失联时长以“刻度 × 毫秒”的精确整数分子累计，除以刻度总量 10000 才是毫秒，
 * 全程不提前取整。</p>
 */
public final class LinkCoverageEvaluator {

    private LinkCoverageEvaluator() {
    }

    /**
     * 评估整条航线的连续失联：逐段求覆盖并集的补集缺口，
     * 前段尾部缺口（触达刻度终点）与后段头部缺口（触达刻度起点）连成同一段。
     */
    public static LinkAssessment evaluate(List<SegmentLink> segments) {
        List<SegmentUnion> union = new ArrayList<>(segments.size());
        List<LinkGap> gaps = new ArrayList<>();
        // 尚未闭合的连续失联区间：仅当上一段尾部缺口触达刻度终点时才保持开放
        boolean active = false;
        int curFrom = 0;
        int curEnd = 0;
        long curLoss = 0;
        for (int i = 0; i < segments.size(); i++) {
            SegmentLink segment = segments.get(i);
            List<CoverageInterval> merged = merge(segment.coverage());
            union.add(new SegmentUnion(i, merged));
            List<int[]> segGaps = gapsOf(merged);
            for (int[] g : segGaps) {
                long loss = (long) (g[1] - g[0]) * segment.durationMs();
                if (active && g[0] == 0) {
                    // 跨段：本段头部缺口接续上一段尾部缺口，连成同一段连续失联
                    curLoss += loss;
                    curEnd = i;
                } else {
                    if (active) {
                        gaps.add(new LinkGap(curFrom, curEnd, curLoss));
                    }
                    active = true;
                    curFrom = i;
                    curEnd = i;
                    curLoss = loss;
                }
            }
            // 本段没有尾部缺口（末尾被覆盖或全段无缺口），连续失联在此闭合
            boolean reachesTail = !segGaps.isEmpty()
                    && segGaps.get(segGaps.size() - 1)[1] == LinkAssessment.SCALE;
            if (active && !reachesTail) {
                gaps.add(new LinkGap(curFrom, curEnd, curLoss));
                active = false;
            }
        }
        if (active) {
            gaps.add(new LinkGap(curFrom, curEnd, curLoss));
        }
        return new LinkAssessment(List.copyOf(union), List.copyOf(gaps));
    }

    /** 合并覆盖闭区间：重叠或端点相接（零时长缺口不计）合并为一条，不双计重叠。 */
    public static List<CoverageInterval> merge(List<CoverageInterval> intervals) {
        if (intervals.isEmpty()) {
            return List.of();
        }
        List<CoverageInterval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparingInt(CoverageInterval::from)
                .thenComparingInt(CoverageInterval::to));
        List<CoverageInterval> merged = new ArrayList<>(sorted.size());
        int from = sorted.get(0).from();
        int to = sorted.get(0).to();
        for (int i = 1; i < sorted.size(); i++) {
            CoverageInterval iv = sorted.get(i);
            if (iv.from() <= to) {
                to = Math.max(to, iv.to());
            } else {
                merged.add(new CoverageInterval(from, to));
                from = iv.from();
                to = iv.to();
            }
        }
        merged.add(new CoverageInterval(from, to));
        return merged;
    }

    /** 合并并集在 [0,10000] 上的补集缺口：[start,end) 刻度区间，长度 end-start。 */
    private static List<int[]> gapsOf(List<CoverageInterval> merged) {
        List<int[]> gaps = new ArrayList<>();
        int prev = 0;
        for (CoverageInterval iv : merged) {
            if (iv.from() > prev) {
                gaps.add(new int[]{prev, iv.from()});
            }
            prev = Math.max(prev, iv.to());
        }
        if (prev < LinkAssessment.SCALE) {
            gaps.add(new int[]{prev, LinkAssessment.SCALE});
        }
        return gaps;
    }
}
