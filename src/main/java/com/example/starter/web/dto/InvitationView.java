package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 邀约视图。
 */
public record InvitationView(
        Long id,
        Instant periodStart,
        Instant periodEnd,
        int rateFenPerKwh,
        Instant createdAt
) {
}
