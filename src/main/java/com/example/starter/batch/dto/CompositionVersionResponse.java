package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 成分版本响应。allergenCodes 为规范化排序后的过敏原代码；tested 表示该版本
 * 是否已有 PASS 检验（放行门禁要求最终血缘集合内各批次当前成分版本均已检验）。
 */
public record CompositionVersionResponse(
        String batchKey,
        int version,
        List<String> allergenCodes,
        String segregationLevel,
        boolean tested,
        Instant createdAt
) {
}
