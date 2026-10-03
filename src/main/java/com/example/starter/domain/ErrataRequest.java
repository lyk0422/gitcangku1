package com.example.starter.domain;

import java.time.Instant;

/**
 * 勘误申请行。冻结后的新称量仅形成勘误记录，不覆盖结算快照。
 */
public record ErrataRequest(
        long id,
        long ticketId,
        int newGrossKg,
        String reason,
        String status,
        String requestId,
        String requestHash,
        Instant createdAtUtc) {

    public static final String STATUS_SUBMITTED = "SUBMITTED";
}