package com.example.starter.web.dto;

import com.example.starter.model.ObservationRequest;

import java.time.Instant;

/**
 * 申请/批准/撤销接口返回的申请视图。
 */
public record RequestView(
        String requestNo,
        String project,
        String instrumentId,
        String researchGroup,
        Instant windowStart,
        Instant windowEnd,
        int durationMinutes,
        String status,
        Instant requestedAt,
        Instant allocatedAt,
        Instant cancelledAt,
        String promotedFromRequestNo
) {

    public static RequestView of(ObservationRequest r) {
        return new RequestView(
                r.getRequestNo(),
                r.getProject(),
                r.getInstrumentId(),
                r.getResearchGroup(),
                r.getWindowStart(),
                r.getWindowEnd(),
                r.getDurationMinutes(),
                r.getStatus().name(),
                r.getRequestedAt(),
                r.getAllocatedAt(),
                r.getCancelledAt(),
                r.getPromotedFromRequestNo()
        );
    }
}
