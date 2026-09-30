package com.example.starter.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import com.example.starter.po.TideSegmentPo;

/**
 * 潮高校核工具：按整个申请区间内的最低有效潮高计算。区间均为左闭右开。
 */
public final class TideCalculator {

    private TideCalculator() {
    }

    /**
     * 判断已排序潮高区间是否无缝覆盖 [windowStart, windowEnd)。
     *
     * @param segments    已按起点升序排序、内部不重叠的区间
     * @param windowStart 申请区间起点（含）
     * @param windowEnd   申请区间终点（不含）
     * @return true 表示区间无缺口地完整覆盖申请窗口
     */
    public static boolean covers(List<TideSegmentPo> segments,
                                 LocalDateTime windowStart, LocalDateTime windowEnd) {
        List<TideSegmentPo> hits = segments.stream()
                .filter(s -> s.startTime().isBefore(windowEnd) && s.endTime().isAfter(windowStart))
                .toList();
        if (hits.isEmpty()) {
            return false;
        }
        TideSegmentPo first = hits.get(0);
        if (first.startTime().isAfter(windowStart) || !first.endTime().isAfter(windowStart)) {
            return false;
        }
        LocalDateTime cursor = first.endTime();
        for (int i = 1; i < hits.size(); i++) {
            TideSegmentPo current = hits.get(i);
            if (current.startTime().isAfter(cursor)) {
                return false;
            }
            if (current.endTime().isAfter(cursor)) {
                cursor = current.endTime();
            }
        }
        return !cursor.isBefore(windowEnd);
    }

    /**
     * 计算申请窗口内各相交区间的最低潮高（米）；调用方须先用 {@link #covers} 确认无缺口。
     */
    public static BigDecimal minHeight(List<TideSegmentPo> segments,
                                       LocalDateTime windowStart, LocalDateTime windowEnd) {
        return segments.stream()
                .filter(s -> s.startTime().isBefore(windowEnd) && s.endTime().isAfter(windowStart))
                .map(TideSegmentPo::height)
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("潮高区间未覆盖申请窗口"));
    }
}
