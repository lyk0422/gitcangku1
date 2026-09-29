package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批响应。父批全部落定 MERGED；产物初始 QUARANTINED，必做检验项为各父批并集，
 * 生产时间取合批发生的 UTC 时点。parents 按请求顺序返回。
 */
public record MergeResponse(
        String childBatchKey,
        String childBatchNo,
        String childProductCode,
        Instant producedAt,
        BatchStatus childStatus,
        List<String> requiredTests,
        List<MergeParent> parents,
        Instant mergedAt
) {

    /**
     * 参与合批的父批概要。
     */
    public record MergeParent(
            String batchKey,
            String batchNo,
            String materialCode
    ) {
    }
}
