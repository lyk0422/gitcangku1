package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

/**
 * 血缘查询条目：批次自身状态 + 导致其不可用的拦截祖先业务键。
 * unavailableDueToRecalledAncestor 为 null 表示没有召回祖先；
 * 批次自身被直接召回（RECALLED）不算祖先召回，该字段仍为 null。
 * unavailableDueToExcursionRejectAncestor 为 null 表示没有 MAJOR 偏差裁决 REJECT 的祖先；
 * REJECT 裁决与其后代按召回口径拦截，但不把后代自身状态改写为 RECALLED。
 */
public record LineageEntryResponse(
        String batchKey,
        String batchNo,
        BatchStatus status,
        String unavailableDueToRecalledAncestor,
        String unavailableDueToExcursionRejectAncestor
) {
}
