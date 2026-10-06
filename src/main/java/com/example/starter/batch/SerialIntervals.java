package com.example.starter.batch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 序号区间工具：闭区间 [start, end]，序号范围 1～1000000。
 * 规范化 = 校验范围 → 按起点排序 → 重叠拒绝（422）→ 相邻合并；
 * 规范化后的区间集合作为存储与幂等指纹的唯一形态，因此区间换序视为同参。
 */
public final class SerialIntervals {

    /**
     * 序号最小值（含）。
     */
    public static final long MIN_SERIAL = 1L;

    /**
     * 序号最大值（含）。
     */
    public static final long MAX_SERIAL = 1_000_000L;

    /**
     * 单个批次允许绑定的区间个数上限（规范化后计）。
     */
    public static final int MAX_INTERVALS = 10;

    private SerialIntervals() {
    }

    /**
     * 闭区间 [start, end]；length 为区间覆盖的序号个数。
     */
    public record Interval(long start, long end) {

        public long length() {
            return end - start + 1;
        }
    }

    /**
     * 规范化区间集合：任一区间越界（不在 1～1000000 或 start &gt; end）或两两重叠抛 422；
     * 相邻区间（前一 end + 1 == 后一 start）合并；规范化后个数须在 1～10 之间。
     */
    public static List<Interval> canonicalize(List<Interval> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.unprocessable("序号区间集合不能为空");
        }
        for (Interval interval : raw) {
            if (interval.start() < MIN_SERIAL || interval.end() > MAX_SERIAL
                    || interval.start() > interval.end()) {
                throw ApiException.unprocessable("序号区间越界 [" + interval.start() + ", "
                        + interval.end() + "]，须在 " + MIN_SERIAL + "～" + MAX_SERIAL + " 闭区间内");
            }
        }
        List<Interval> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingLong(Interval::start).thenComparingLong(Interval::end));
        List<Interval> merged = new ArrayList<>(sorted.size());
        for (Interval next : sorted) {
            if (merged.isEmpty()) {
                merged.add(next);
                continue;
            }
            Interval last = merged.get(merged.size() - 1);
            if (next.start() <= last.end()) {
                throw ApiException.unprocessable("序号区间重叠 [" + last.start() + ", " + last.end()
                        + "] 与 [" + next.start() + ", " + next.end() + "]");
            }
            if (next.start() == last.end() + 1) {
                merged.set(merged.size() - 1, new Interval(last.start(), next.end()));
            } else {
                merged.add(next);
            }
        }
        if (merged.size() > MAX_INTERVALS) {
            throw ApiException.unprocessable("序号区间个数 " + merged.size() + " 超出上限 "
                    + MAX_INTERVALS);
        }
        return merged;
    }

    /**
     * 区间集合总长度（覆盖的序号个数）。
     */
    public static long totalLength(List<Interval> intervals) {
        long total = 0;
        for (Interval interval : intervals) {
            total += interval.length();
        }
        return total;
    }

    /**
     * 规范化区间集合的指纹形态，如 "1-4,5-10"；同一集合无论输入顺序如何结果一致。
     */
    public static String canonicalString(List<Interval> canonical) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < canonical.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(canonical.get(i).start()).append('-').append(canonical.get(i).end());
        }
        return sb.toString();
    }
}
