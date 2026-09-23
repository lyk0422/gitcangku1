package com.example.starter.consent.migration;

/**
 * 左闭右开的长整型处理范围：[start, end)，单位为记录属性空间内的整数值。
 *
 * @param start 起点（含）
 * @param end   终点（不含），必须满足 start &lt; end
 */
public record LongRange(long start, long end) {

    public boolean contains(long value) {
        return value >= start && value < end;
    }

    public boolean isEmpty() {
        return start >= end;
    }

    /**
     * 本范围是否为 other 的子集（含相等）。
     */
    public boolean isSubsetOf(LongRange other) {
        return start >= other.start && end <= other.end;
    }

    /**
     * 两个范围是否在整数点上重叠。
     */
    public boolean overlaps(LongRange other) {
        return start < other.end && other.start < end;
    }
}
