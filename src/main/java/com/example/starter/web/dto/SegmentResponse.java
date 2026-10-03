package com.example.starter.web.dto;

import com.example.starter.model.SegmentRow;
import java.time.Instant;

/**
 * 航段响应。
 */
public record SegmentResponse(long id, String vesselCode, int requiredLevel, Instant estStartUtc,
                              Instant estEndUtc, String boardingPoint, String status,
                              Instant createdAtUtc, Instant cancelledAtUtc) {

    public static SegmentResponse from(SegmentRow row) {
        return new SegmentResponse(row.id(), row.vesselCode(), row.requiredLevel(),
                row.estStartUtc(), row.estEndUtc(), row.boardingPoint(), row.status().name(),
                row.createdAtUtc(), row.cancelledAtUtc());
    }
}