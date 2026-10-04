package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 批量保障改签单项结果：后车原始发平移到新始发，shiftMinutes 为实际平移分钟数。
 */
public record GuaranteeRescheduleItem(String scheduleKey, int version,
                                      Instant oldFirstStartUtc, Instant newFirstStartUtc,
                                      long shiftMinutes) {
}