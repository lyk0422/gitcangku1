package com.example.starter.batch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 序号区间纯函数工具：闭区间整数序号，范围 1..1000000。
 * 规范化规则：按起始序号升序排序；区间内重叠拒绝（422）；相邻区间（前一区间终点+1
 * 等于后一区间起点）合并。全程只操作区间端点，不展开为逐件序号。
 */
public final class SerialIntervals {

    public static final int MIN_SERIAL = 1;
    public static final int MAX_SERIAL = 1_000_000;

    private SerialIntervals() {
    }

    /**
     * 规范化后的闭区间，start &lt;= end，两端均在 1..1000000 内。
     */
    public record Interval(int start, int end) {

        public int length() {
            return end - start + 1;
        }

        public boolean contains(int serial) {
            return start <= serial && serial <= end;
        }

        public boolean overlaps(Interval other) {
            return start <= other.end && other.start <= end;
        }
    }

    /**
     * 规范化一组区间：校验边界与起止顺序，排序后合并相邻区间，重叠则抛 422。
     * 返回按起始序号升序、两两不相交且互不相邻的区间列表。
     */
    public static List<Interval> normalize(List<int[]> raw) {
        List<Interval> sorted = new ArrayList<>(raw.size());
        for (int[] pair : raw) {
            int start = pair[0];
            int end = pair[1];
            if (start > end) {
                throw ApiException.unprocessable("区间起点不能大于终点: [" + start + "," + end + "]");
            }
            if (start < MIN_SERIAL || end > MAX_SERIAL) {
                throw ApiException.unprocessable("区间超出序号范围 " + MIN_SERIAL + ".." + MAX_SERIAL
                        + ": [" + start + "," + end + "]");
            }
            sorted.add(new Interval(start, end));
        }
        sorted.sort(Comparator.comparingInt(Interval::start));
        List<Interval> merged = new ArrayList<>(sorted.size());
        for (Interval next : sorted) {
            if (merged.isEmpty()) {
                merged.add(next);
                continue;
            }
            Interval prev = merged.get(merged.size() - 1);
            if (next.start() <= prev.end()) {
                throw ApiException.unprocessable(
                        "区间重叠: [" + prev.start() + "," + prev.end() + "] 与 ["
                                + next.start() + "," + next.end() + "]");
            }
            if (next.start() == prev.end() + 1) {
                merged.set(merged.size() - 1, new Interval(prev.start(), next.end()));
            } else {
                merged.add(next);
            }
        }
        return merged;
    }

    /**
     * 区间列表总长度（件数）。
     */
    public static long totalLength(List<Interval> intervals) {
        long total = 0;
        for (Interval interval : intervals) {
            total += interval.length();
        }
        return total;
    }

    /**
     * 判定 candidate 是否完整落在 domain 的某一区间内（domain 已规范化，互不相邻）。
     */
    public static boolean containedInAny(Interval candidate, List<Interval> domain) {
        for (Interval d : domain) {
            if (d.start() <= candidate.start() && candidate.end() <= d.end()) {
                return true;
            }
        }
        return false;
    }
}
