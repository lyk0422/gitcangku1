package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 潮高校核结果：整个申请区间按左闭右开拼接覆盖后的最低有效潮高。
 *
 * @param tideVersionId   校核所用潮高版本 id，无任何版本时为空
 * @param minTideHeightM  区间内最低潮高，单位米
 * @param availableDepthM 可用深度 = 静态水深 + 最低潮高，单位米
 */
public record DepthCheck(Long tideVersionId,
                         BigDecimal minTideHeightM,
                         BigDecimal availableDepthM) {

    /**
     * 判断潮高区间集合是否无缝覆盖 [start, end)，允许相邻区间首尾相接，区间可超出申请范围。
     *
     * @param windows 已按起始时间升序的潮高区间
     * @param start   申请起始时间（含）
     * @param end     申请结束时间（不含）
     * @return true 表示无缺口
     */
    public static boolean coversWholeRange(List<TideWindow> windows, LocalDateTime start, LocalDateTime end) {
        if (windows.isEmpty()) {
            return false;
        }
        LocalDateTime cursor = start;
        for (TideWindow window : windows) {
            // 区间之间不允许出现空档；晚于当前游标的起始即构成缺口。
            if (window.startTime().isAfter(cursor)) {
                return false;
            }
            if (window.endTime().isAfter(cursor)) {
                cursor = window.endTime();
            }
            if (!cursor.isBefore(end)) {
                return true;
            }
        }
        return !cursor.isBefore(end);
    }

    /**
     * 取与申请区间相交部分的最低潮高。
     *
     * @param windows 潮高区间
     * @return 最低潮高，单位米；集合为空时返回 null
     */
    public static BigDecimal minTideHeight(List<TideWindow> windows) {
        return windows.stream()
                .map(TideWindow::tideHeightM)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }
}
