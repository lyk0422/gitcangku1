package com.example.starter.web.dto;

import com.example.starter.model.ObservationRequest;

import java.time.Instant;

/**
 * 申请视图：状态与（如有）接替来源。
 */
public record RequestView(
        String requestNo,
        String projectId,
        String instrumentId,
        Instant startAt,
        Instant endAt,
        int durationMinutes,
        String status,
        Instant requestedAt,
        String promotedFromRequestNo,
        Instant promotedAt) {

    public static RequestView from(ObservationRequest r, String promotedFromRequestNo) {
        return new RequestView(
                r.requestNo(), r.projectId(), r.instrumentId(),
                r.startAt(), r.endAt(), r.durationMinutes(),
                r.status().name(), r.requestedAt(),
                promotedFromRequestNo, r.promotedAt());
    }
}
