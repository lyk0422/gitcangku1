package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批成功响应，即子批冻结依据的完整快照：子批固化父批的过敏原集合与实际使用的清洁代次，
 * parents 逐父给出合批落定前的过敏原集合、清洁代次与状态。后续过敏原保存或清洁推进不改写本快照。
 */
public record MergeResponse(
        String requestId,
        String childBatchKey,
        String childBatchNo,
        String productCode,
        List<String> allergens,
        int cleaningGeneration,
        BatchStatus childStatus,
        List<MergeParent> parents,
        Instant mergedAt
) {

    /**
     * 单个父批的冻结依据快照。statusBeforeMerge 固定为 RELEASED；合批落定后父批当前状态为 MERGED。
     */
    public record MergeParent(
            String batchKey,
            String batchNo,
            String productCode,
            List<String> allergens,
            int cleaningGeneration,
            BatchStatus statusBeforeMerge,
            int seq
    ) {
    }
}
