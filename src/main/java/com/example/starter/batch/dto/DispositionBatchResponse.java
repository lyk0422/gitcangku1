package com.example.starter.batch.dto;

import com.example.starter.batch.DispositionCategory;

import java.util.List;

/**
 * 处置单内单个批次的不可变快照条目。
 * path 为提交时冻结的“祖先→…→该批次”完整业务键路径；depth 为距祖先层数（祖先为 0）。
 */
public record DispositionBatchResponse(
        String batchKey,
        DispositionCategory category,
        String frozenStatus,
        long frozenVersion,
        List<String> path,
        int depth,
        int seq
) {
}
