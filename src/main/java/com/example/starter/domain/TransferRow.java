package com.example.starter.domain;

import java.time.Instant;

/**
 * 站点间交接单：当前持有站点发起、目标站点确认后持有权才转移。
 * initiatedAt/confirmedAt 均为 UTC；confirmedAt 为 null 表示未确认。
 */
public record TransferRow(
        Long id,
        String tagCode,
        String fromStationCode,
        String toStationCode,
        TransferStatus status,
        Instant initiatedAt,
        Instant confirmedAt,
        String requestId) {
}