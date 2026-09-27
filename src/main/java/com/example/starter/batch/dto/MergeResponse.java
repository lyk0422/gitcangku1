package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 合批响应。父批状态落定 MERGED；子批初始 QUARANTINED，初始效价为全部父批
 * 在合批时刻当前效价的加权平均（舍入到 4 位小数 HALF_UP）。
 */
public record MergeResponse(
        String childBatchKey,
        BatchStatus childStatus,
        BigDecimal childWeight,
        BigDecimal childInitialPotency,
        List<MergeParent> parents,
        Instant mergedAt
) {

    /**
     * 合批父批结果项：含合批时刻评估的当前效价（actual）、该父批放行阈值（required）
     * 及差额（difference = currentPotency - minReleasePotency）。
     */
    public record MergeParent(
            String batchKey,
            BatchStatus status,
            BigDecimal weight,
            BigDecimal currentPotency,
            BigDecimal requiredPotency,
            BigDecimal difference
    ) {
    }
}
