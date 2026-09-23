package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.util.List;

/**
 * 召回祖先当前后代闭包（含祖先自身）只读查询条目。
 * status/version 为查询当时值（未冻结）；path 为“祖先→…→该批次”完整业务键路径；
 * depth 为距祖先层数（祖先为 0）；seq 为按血缘创建顺序广度优先展开序号（祖先为 1）。
 */
public record ClosureEntryResponse(
        String batchKey,
        String batchNo,
        BatchStatus status,
        long version,
        List<String> path,
        int depth,
        int seq
) {
}
