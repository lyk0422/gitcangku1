package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 工单窗口内批量登记的单条读数。
 *
 * @param readingId          读数标识，设备内唯一，批次内亦不可重复
 * @param sampledAt          UTC 采样时刻，必须落在工单窗口 [windowStart, windowEnd) 内
 * @param cumulativeMinutes  累计工时（分钟），不得低于基线
 */
public record WorkOrderReadingItem(
        @NotBlank String readingId,
        @NotNull Instant sampledAt,
        @NotNull @PositiveOrZero Long cumulativeMinutes) {
}
