package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 工单批量登记读数的单条读数项。
 *
 * @param readingId          读数标识，设备内唯一
 * @param sampledAt          UTC 采样时刻，须落在工单窗口 [windowStart, windowEnd) 内
 * @param cumulativeMinutes  累计工时（分钟），非负且不得低于工单基线
 */
public record WorkOrderReadingItem(
        @NotBlank String readingId,
        @NotNull Instant sampledAt,
        @NotNull @PositiveOrZero Long cumulativeMinutes) {
}
