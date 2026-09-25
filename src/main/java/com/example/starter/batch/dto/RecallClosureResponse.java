package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.util.List;

/**
 * 召回闭包查询结果：rootBatchKey 为被直接召回（或查询起点）的祖先批次，
 * entries 按血缘创建顺序广度优先展开，包含起点自身及其全部后代
 * （同时沿 SPLIT 拆分边与 REWORK 返工边向下扩展，含返工后代的拆分、合批产物）。
 */
public record RecallClosureResponse(
        String rootBatchKey,
        List<ClosureEntry> entries
) {

    /**
     * 闭包内单个批次。
     * directlyRecalled 为 true 表示该批次自身被直接召回（状态 RECALLED）；
     * markedForDisposal 为 true 表示它是闭包内已放行的后代，召回事务中被同事务标记为待处置
     * （状态 PENDING_DISPOSAL）；unavailableDueToRecalledAncestor 给出使其不可用的召回祖先业务键，
     * 起点自身被直接召回时该字段为 null。
     */
    public record ClosureEntry(
            String batchKey,
            String batchNo,
            BatchStatus status,
            boolean directlyRecalled,
            boolean markedForDisposal,
            String unavailableDueToRecalledAncestor
    ) {
    }
}
