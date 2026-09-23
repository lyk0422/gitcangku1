package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 签名信任策略视图，keyIds 按字典序升序返回。
 */
public record PolicyResponse(
        long policyVersion,
        List<String> keyIds,
        int thresholdM,
        Instant effectiveAt,
        Instant createdAt) {
}
