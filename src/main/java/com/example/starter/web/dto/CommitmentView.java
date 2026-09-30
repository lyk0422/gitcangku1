package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 承诺视图。
 */
public record CommitmentView(
        Long id,
        Long invitationId,
        String userId,
        BigDecimal reductionKwh,
        String requestId,
        Instant createdAt
) {
}
