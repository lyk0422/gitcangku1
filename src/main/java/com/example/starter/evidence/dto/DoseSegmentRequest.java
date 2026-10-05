package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 归还时上报的一段光照区间。区间按 UTC 解释，互不重叠且完整覆盖实际借出至归还；
 * 照度非负、最多3位小数；本段剂量=照度×精确分钟数，不舍入。
 *
 * @param startAt        段起始时刻（UTC）
 * @param endAt          段结束时刻（UTC），须晚于起始
 * @param illuminanceLux 照度，单位勒克斯，非负最多3位小数
 */
public record DoseSegmentRequest(
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt,
        @NotNull BigDecimal illuminanceLux) {
}
