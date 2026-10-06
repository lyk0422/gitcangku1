package com.example.starter.evidence.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 归还时上报的一段光照区间（UTC，左闭右开）。一次归还须包含 1 至 20 段，
 * 自首段等于实际借出时刻起首尾相接、互不重叠，完整覆盖至归还时刻。
 *
 * @param startAt 段开始时刻（UTC，含）
 * @param endAt   段结束时刻（UTC，不含），须晚于开始时刻
 * @param lux     照度，单位勒克斯，非负最多3位小数
 */
public record DoseSegmentRequest(
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt,
        @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal lux) {
}
