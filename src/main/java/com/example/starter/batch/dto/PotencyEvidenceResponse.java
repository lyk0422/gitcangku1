package com.example.starter.batch.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 效价证据响应：放行/拆分/合批成功时固化的计算时刻、模型版本、输入效价与舍入结果。
 * 证据只增不改，后续模型修订不回写；threshold 在 SPLIT/MERGE 证据上为 null（null 语义稳定）。
 */
public record PotencyEvidenceResponse(
        long id,
        String batchKey,
        String operation,
        Instant computedAt,
        int modelVersion,
        JsonNode inputs,
        BigDecimal rawPotency,
        BigDecimal roundedPotency,
        BigDecimal threshold,
        Instant createdAt
) {
}
