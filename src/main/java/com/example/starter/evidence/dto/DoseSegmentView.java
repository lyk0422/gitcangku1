package com.example.starter.evidence.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 剂量段视图。历史冻结，不可变。
 *
 * @param seq            段序号，从1开始按时间顺序排列
 * @param startAt        段区间起点（UTC，含）
 * @param endAt          段区间终点（UTC，不含）
 * @param lux            照度（勒克斯），非负，最多3位小数
 * @param doseLuxMinutes 本段剂量=照度×精确分钟（勒克斯分钟），不舍入
 * @param doseDisplay    本段剂量显示值，HALF_UP 保留4位小数，仅用于展示不参与判定
 */
public record DoseSegmentView(
        int seq,
        LocalDateTime startAt,
        LocalDateTime endAt,
        BigDecimal lux,
        BigDecimal doseLuxMinutes,
        String doseDisplay) {
}
