package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.util.List;

/**
 * 拆分结果：父批进入 SPLIT 并不再可用；子批初始 QUARANTINED，按请求顺序返回。
 */
public record SplitResponse(
        String parentBatchKey,
        BatchStatus parentStatus,
        List<BatchResponse> children
) {
}
