package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 规范版本响应：完整特性与约束定义，版本只追加不修改。
 */
public record SpecVersionResponse(
        String specKey,
        int version,
        List<String> features,
        List<SpecConstraintInput> constraints,
        String operator,
        Instant createdAt
) {
}
