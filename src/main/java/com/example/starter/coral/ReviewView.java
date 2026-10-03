package com.example.starter.coral;

import java.time.Instant;

/**
 * 复核链中的一条复核记录，reviewedAt为UTC时刻。
 */
public record ReviewView(String requestId, TransplantStatus result, Instant reviewedAt) {
}