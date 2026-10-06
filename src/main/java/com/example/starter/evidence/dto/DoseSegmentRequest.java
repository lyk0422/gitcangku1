package com.example.starter.evidence.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 归还剂量段请求。一次归还携带1至20段；区间互不重叠且完整覆盖实际借出至归还。
 *
 * @param startAt 段区间起点（UTC，含）
 * @param endAt   段区间终点（UTC，不含），须晚于起点
 * @param lux     照度（勒克斯），非负，最多3位小数
 */
public record DoseSegmentRequest(
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt,
        @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 3) BigDecimal lux) {
}
