package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 接续关系视图。requiredMinutes 为保障要求，actualMinutes 为当前实际接续分钟数，
 * marginMinutes 为富余（actual - required，负数表示不足）；cancelledAt 未取消时为 null。
 */
public record ConnectionResponse(long id, String predecessorKey, String successorKey,
                                 int guaranteeMinutes, boolean crossesMidnight, String status,
                                 int requiredMinutes, long actualMinutes, long marginMinutes,
                                 Instant createdAt, Instant cancelledAt) {
}