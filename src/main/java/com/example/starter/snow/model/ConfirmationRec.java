package com.example.starter.snow.model;

import java.time.Instant;

/**
 * 审核确认记录行。
 *
 * @param id 主键
 * @param reviewId 关联审核主键
 * @param reviewer 审核员标识
 * @param seq 确认次序：1=第一人，2=第二人
 * @param requestId 幂等键
 * @param createdAt 确认时间（UTC）
 */
public record ConfirmationRec(long id, long reviewId, String reviewer, int seq,
                              String requestId, Instant createdAt) {
}