package com.example.starter.api.dto;

/**
 * 占用历史事件（按 occurredAt、eventType 升序，字段与时间精度稳定）。
 *
 * @param eventType          事件类型：OCCUPATION_CREATED / OCCUPATION_YIELDED /
 *                           OCCUPATION_DEPARTED / OCCUPATION_REAPPLIED（作为来源被接替）/
 *                           OCCUPATION_CREATED_BY_REAPPLY（由重新申请生成）
 * @param occurredAt         事件发生时间，epoch 毫秒（UTC）
 * @param corridorId         让渡事件关联的走廊标识；其他事件为 null
 * @param corridorVersion    让渡发生时的走廊版本；其他事件为 null
 * @param reviewId           关联的审查记录标识；起飞事件为 null
 * @param relatedOccupationId 重新申请关联的对端占用标识；其他事件为 null
 * @param reason             让渡原因；其他事件为 null
 */
public record OccupationHistoryEvent(String eventType, long occurredAt,
                                     String corridorId, Integer corridorVersion,
                                     String reviewId, String relatedOccupationId,
                                     String reason) {
}
