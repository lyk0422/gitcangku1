package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批响应：新子批概要（初始 QUARANTINED）与参与合批的父批键（按请求顺序）。
 * 子批继承父批产品编码与必做检验项（并集，按父批顺序首次出现），不继承检验、批准与仓储记录。
 */
public record MergeResponse(
        String childKey,
        String childBatchNo,
        String productCode,
        BatchStatus status,
        List<String> parentKeys,
        List<String> requiredTests,
        Instant createdAt
) {
}
