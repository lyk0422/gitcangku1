package com.example.starter.web.dto;

import com.example.starter.domain.TakeoffClearance;
import java.time.Instant;

/** 起飞确认记录视图，status为CLEARED或REJECTED，拒绝时reason非空。 */
public record ClearanceResponse(long clearanceId, long flightId, Long completionId,
        String requestId, String status, String reason, Instant confirmedAt) {

    public static ClearanceResponse of(TakeoffClearance clearance) {
        return new ClearanceResponse(clearance.id(), clearance.flightId(), clearance.completionId(),
                clearance.requestId(), clearance.status().name(), clearance.reason(),
                clearance.confirmedAt());
    }
}