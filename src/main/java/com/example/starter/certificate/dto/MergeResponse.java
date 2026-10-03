package com.example.starter.certificate.dto;

import java.time.Instant;
import java.util.List;

/**
 * 合批响应：parentCount 为实际合批父批数（实际值），parents 按请求顺序（稳定排序）。
 */
public record MergeResponse(
        String childBatchKey,
        String childStatus,
        String productCode,
        int parentCount,
        List<MergeParent> parents,
        String declarationKey,
        int declarationVersion,
        String matchedPrefix,
        Instant mergedAt
) {

    /**
     * 合批父批结果项：合批提交后父批状态恒为 MERGED。
     */
    public record MergeParent(String batchKey, String status) {
    }
}