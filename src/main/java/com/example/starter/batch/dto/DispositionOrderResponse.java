package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.DispositionCategory;

import java.time.Instant;
import java.util.List;

/**
 * 处置单查询响应：处置单元数据 + 提交时冻结的闭包批次（版本/状态/路径/分类）。
 * 只读；冻结视图在提交后不可改写。
 */
public record DispositionOrderResponse(
        String dispositionKey,
        String ancestorKey,
        String status,
        int version,
        String submitActor,
        String decideActor,
        String holdReason,
        Instant submittedAt,
        Instant decidedAt,
        List<FrozenBatch> batches
) {

    /**
     * 提交时冻结的单个闭包批次。
     * frozenPath 为到召回祖先的完整祖先链业务键（祖先在前、不含批次自身）；祖先自身为空列表。
     */
    public record FrozenBatch(
            String batchKey,
            DispositionCategory category,
            long frozenVersion,
            BatchStatus frozenStatus,
            List<String> frozenPath,
            int seq
    ) {
    }
}
