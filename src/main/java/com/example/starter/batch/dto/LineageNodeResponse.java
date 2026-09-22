package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

/**
 * 血缘查询节点：批次自身状态 + 导致其不可用的最近召回祖先 batchKey；
 * recalledAncestor 为 null 表示不存在召回祖先（自身被直接召回时不回填该字段）。
 */
public record LineageNodeResponse(
        String batchKey,
        String productCode,
        String batchNo,
        BatchStatus status,
        String recalledAncestor
) {
}
