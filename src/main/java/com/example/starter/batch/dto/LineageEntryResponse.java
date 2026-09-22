package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

/**
 * 血缘查询条目：批次自身状态 + 导致其不可用的召回祖先业务键。
 * unavailableDueToRecalledAncestor 为 null 表示没有召回祖先；
 * 批次自身被直接召回（RECALLED）不算祖先召回，该字段仍为 null。
 */
public record LineageEntryResponse(
        String batchKey,
        String batchNo,
        BatchStatus status,
        String unavailableDueToRecalledAncestor
) {
}
