package com.example.starter.api.dto;

/**
 * 占用处置不可变事件条目。固定按 seq 升序；时间精度 epoch 毫秒（UTC）；
 * 不适用的关联字段为 null（null 语义稳定，不随状态变化回写）。
 *
 * @param seq                  自增序号
 * @param occupancyId          所属占用标识
 * @param eventType            CREATED / YIELDED / REAPPLIED / DEPARTED
 * @param eventTime            事件时间 epoch 毫秒（UTC）
 * @param corridorId           YIELDED 事件的走廊标识；其他事件为 null
 * @param relatedOccupancyId   REAPPLIED 事件的新占用标识；其他事件为 null
 * @param reviewId             CREATED 事件的审查标识；其他事件为 null
 * @param reason               YIELDED 事件的让渡原因；其他事件为 null
 */
public record OccupancyEventDto(long seq, String occupancyId, String eventType, long eventTime,
                                String corridorId, String relatedOccupancyId,
                                String reviewId, String reason) {
}
