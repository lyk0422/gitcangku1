package com.example.starter.domain;

import java.time.Instant;

/**
 * 起飞确认记录，放行与拒绝均留痕，历史不可覆盖。
 * completionId在作业缺失类拒绝时为空；reason在放行时为空。
 */
public record TakeoffClearance(long id, long flightId, Long completionId, String requestId,
        String requestHash, ClearanceStatus status, String reason, Instant confirmedAt) {
}