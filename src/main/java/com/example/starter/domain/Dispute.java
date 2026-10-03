package com.example.starter.domain;

import java.time.Instant;

/**
 * 争议裁决行。status：OPEN/RESOLVED；adoptedSource：ORIGINAL/REWEIGH，未裁决为 NULL。
 */
public record Dispute(
        long id,
        long ticketId,
        String status,
        String adoptedSource,
        Long adoptedReweighId,
        String adjudicationReason,
        String adjudicator,
        Instant adjudicatedAtUtc,
        String requestId,
        String requestHash,
        Instant createdAtUtc) {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String SOURCE_ORIGINAL = "ORIGINAL";
    public static final String SOURCE_REWEIGH = "REWEIGH";
}