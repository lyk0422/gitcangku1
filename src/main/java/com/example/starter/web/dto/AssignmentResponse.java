package com.example.starter.web.dto;

import com.example.starter.model.AssignmentRow;
import java.time.Instant;

/**
 * 指派响应。
 */
public record AssignmentResponse(long id, long segmentId, long pilotId, String requestId,
                                 String status, Instant boardedAtUtc, Instant disembarkedAtUtc,
                                 Instant createdAtUtc, Instant releasedAtUtc,
                                 String releaseReason) {

    public static AssignmentResponse from(AssignmentRow row) {
        return new AssignmentResponse(row.id(), row.segmentId(), row.pilotId(), row.requestId(),
                row.status().name(), row.boardedAtUtc(), row.disembarkedAtUtc(),
                row.createdAtUtc(), row.releasedAtUtc(), row.releaseReason());
    }
}