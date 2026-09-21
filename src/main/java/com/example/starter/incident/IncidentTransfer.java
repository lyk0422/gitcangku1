package com.example.starter.incident;

import java.time.Instant;

/**
 * 指挥交接单实体，对应 incident_transfers 表。
 * acceptedAt 仅 ACCEPTED 状态有值，其余为 null。
 */
public record IncidentTransfer(
        long id,
        long incidentId,
        String fromCommander,
        String toCommander,
        TransferStatus status,
        Instant createdAt,
        Instant acceptedAt) {
}
