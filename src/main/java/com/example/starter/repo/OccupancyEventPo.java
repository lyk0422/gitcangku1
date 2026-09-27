package com.example.starter.repo;

/**
 * 占用处置不可变事件记录。固定按 seq 升序排序。
 *
 * @param seq                  自增序号（决定事件排序）
 * @param occupancyId          事件所属占用标识
 * @param eventType            CREATED / YIELDED / REAPPLIED / DEPARTED
 * @param eventTime            事件时间 epoch 毫秒（UTC）
 * @param corridorId           YIELDED 事件关联走廊；其他事件为 null
 * @param relatedOccupancyId   REAPPLIED 事件关联的新占用；其他事件为 null
 * @param reviewId             CREATED 事件依据的审查；其他事件为 null
 * @param reason               YIELDED 事件的让渡原因；其他事件为 null
 */
public record OccupancyEventPo(long seq, String occupancyId, String eventType, long eventTime,
                               String corridorId, String relatedOccupancyId,
                               String reviewId, String reason) {
}
