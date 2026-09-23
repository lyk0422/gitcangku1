package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

/**
 * 批次当前持有厂查询视图：只读。
 */
public record HoldingResponse(
        String batchKey,
        String holdingPlant,
        int batchVersion,
        BatchStatus status
) {
}
