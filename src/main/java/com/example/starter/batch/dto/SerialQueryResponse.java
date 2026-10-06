package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

/**
 * 序号归属查询响应：返回该序号在当前血缘树中的叶批及其召回状态。
 * recalled 为 true 表示叶批自身被召回或任一祖先被召回；recalledBy 为导致
 * 召回状态的批次业务键（自身或最近的召回祖先），未召回时为 null。
 */
public record SerialQueryResponse(
        String productCode,
        long serial,
        String batchKey,
        String batchNo,
        BatchStatus status,
        boolean recalled,
        String recalledBy
) {
}
