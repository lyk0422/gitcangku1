package com.example.starter.domain;

import java.time.Instant;

/**
 * 称量单行。重量单位公斤，时间为 UTC。
 * tareKg/grossKg 为 NULL 表示对应称量尚未登记；status：OPEN/GROSSED/FROZEN。
 */
public record WeighTicket(
        long id,
        String ticketNo,
        String transportOrderNo,
        String vehicleNo,
        Integer tareKg,
        Integer grossKg,
        Instant tareTimeUtc,
        Instant grossTimeUtc,
        String status,
        String createRequestId,
        String createRequestHash,
        String tareRequestId,
        String tareRequestHash,
        String grossRequestId,
        String grossRequestHash,
        Instant createdAtUtc) {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_GROSSED = "GROSSED";
    public static final String STATUS_FROZEN = "FROZEN";
}