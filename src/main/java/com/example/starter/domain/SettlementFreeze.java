package com.example.starter.domain;

import java.time.Instant;

/**
 * 最终净重冻结快照行。netKg = adoptedGrossKg - tareKg（公斤，非负），冻结后不可覆盖。
 */
public record SettlementFreeze(
        long id,
        long ticketId,
        int tareKg,
        int adoptedGrossKg,
        int netKg,
        String adoptedSource,
        Long adoptedReweighId,
        String reweighVersionSet,
        String requestId,
        String requestHash,
        Instant frozenAtUtc) {
}