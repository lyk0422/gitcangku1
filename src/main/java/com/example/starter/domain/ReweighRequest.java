package com.example.starter.domain;

import java.time.Instant;

/**
 * 复称申请行。grossKg 为复称毛重（公斤）；status：SUBMITTED/ADOPTED/REJECTED。
 */
public record ReweighRequest(
        long id,
        long ticketId,
        int grossKg,
        String reason,
        String status,
        String requestId,
        String requestHash,
        Instant createdAtUtc) {

    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_ADOPTED = "ADOPTED";
    public static final String STATUS_REJECTED = "REJECTED";
}