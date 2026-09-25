package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.util.List;

/**
 * 召回闭包查询：目标批次被直接召回（或其自身就是已召回祖先）时，
 * 闭包沿 SPLIT 与 REWORK 两类血缘边向下展开，包含返工后代及其拆分、合批产物。
 *
 * @param recalledRootBatchKey 直接召回的祖先批次业务键（查询目标自身或其最近召回祖先）
 * @param closure              闭包内全部批次（不含 recalledRootBatchKey 自身），按血缘创建顺序排列
 */
public record RecallClosureResponse(
        String queriedBatchKey,
        String recalledRootBatchKey,
        List<ClosureEntry> closure
) {

    /**
     * 召回闭包条目。disposition 为该批次受召回影响的处置方式：
     * BLOCKED 表示拦截放行（状态未改写，禁止新增检验/批准/返工/拆分）；
     * PENDING_DISPOSAL 表示已放行批次在召回事务内被标记为待处置。
     */
    public record ClosureEntry(
            String batchKey,
            String batchNo,
            BatchStatus status,
            String disposition
    ) {
    }
}
