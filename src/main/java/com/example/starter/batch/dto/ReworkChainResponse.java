package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.util.List;

/**
 * 返工链明细：沿 REWORK 边从初始代次（generation=0）的链头逐级展开到最新返工产物。
 * anchorBatchKey 为被查询批次自身或其在返工链上的祖先（拆分子批继承代次但不位于返工链脊柱上）。
 * 每个条目携带代次、状态；非链头条目额外携带创建它的 reworkKey 与返工说明。
 */
public record ReworkChainResponse(
        String queriedBatchKey,
        String anchorBatchKey,
        List<Entry> chain
) {

    /**
     * 返工链脊柱条目。reworkKey/reworkReason 仅对返工产物（generation &gt;= 1）非空。
     */
    public record Entry(
            String batchKey,
            String batchNo,
            BatchStatus status,
            int generation,
            String reworkKey,
            String reworkReason
    ) {
    }
}
