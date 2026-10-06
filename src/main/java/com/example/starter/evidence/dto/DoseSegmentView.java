package com.example.starter.evidence.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一段冻结的光照区间视图。
 *
 * @param seq            段序号，从1开始
 * @param startAt        段开始时刻（UTC，含）
 * @param endAt          段结束时刻（UTC，不含）
 * @param lux            照度（勒克斯，非负最多3位小数）
 * @param doseLuxMinutes 本段剂量展示值（勒克斯分钟，HALF_UP 四位）
 */
public record DoseSegmentView(
        int seq,
        LocalDateTime startAt,
        LocalDateTime endAt,
        BigDecimal lux,
        BigDecimal doseLuxMinutes) {
}
