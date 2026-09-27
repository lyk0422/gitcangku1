package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 合批父贡献明细响应：合批子批的每个父批在操作时刻固化的实际投入数量、权重、
 * 输入效价；按 seq（规范化 batchKey 升序）稳定排序，创建后不可改写。
 */
public record ContributionResponse(
        int seq,
        String parentBatchKey,
        BigDecimal quantity,
        BigDecimal weight,
        BigDecimal potencyAtMerge,
        Instant mergedAt
) {
}
