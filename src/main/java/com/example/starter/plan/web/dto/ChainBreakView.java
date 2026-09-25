package com.example.starter.plan.web.dto;

/**
 * 不可变断链记录视图。
 *
 * @param reason 断链原因，如 CANCEL_MIDDLE
 */
public record ChainBreakView(long id, String stockNo, String opDate,
                             String cancelledScheduleKey, String predecessorScheduleKey,
                             String successorScheduleKey, String reason, long createdAt) {
}
