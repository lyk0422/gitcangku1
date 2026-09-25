package com.example.starter.plan.web.dto;

import java.time.LocalDate;

/**
 * 断链记录视图。prevScheduleKey 为 null 表示被移除段原为链首。
 */
public record ChainBreakView(long id, String stockKey, LocalDate opDate,
                             String cancelledScheduleKey, String prevScheduleKey,
                             String nextScheduleKey, long createdAt) {
}
