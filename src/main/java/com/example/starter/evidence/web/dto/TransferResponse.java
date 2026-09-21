package com.example.starter.evidence.web.dto;

import com.example.starter.evidence.domain.Transfer;
import com.example.starter.evidence.domain.TransferStatus;

import java.time.LocalDateTime;

/**
 * 交接记录视图。
 */
public record TransferResponse(
        Long id,
        String evidenceKey,
        String fromCustodianId,
        String toCustodianId,
        TransferStatus status,
        LocalDateTime createdAt,
        LocalDateTime decidedAt
) {
    public static TransferResponse from(Transfer transfer, String evidenceKey) {
        return new TransferResponse(
                transfer.id(),
                evidenceKey,
                transfer.fromCustodianId(),
                transfer.toCustodianId(),
                transfer.status(),
                transfer.createdAt(),
                transfer.decidedAt());
    }
}
