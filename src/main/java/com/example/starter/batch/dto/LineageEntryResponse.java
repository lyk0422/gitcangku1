package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.util.List;

/**
 * 血缘查询条目：批次自身状态 + 导致其不可用的全部召回祖先业务键。
 * 多父有向无环图中不同路径可能各自存在召回祖先，召回一条路径不能被另一条
 * 未召回路径抵消，因此这里列出经任一路径可达的全部直接召回（RECALLED）祖先，
 * 按 batchKey 升序去重；为空列表表示不存在召回祖先。
 * 批次自身被直接召回（RECALLED）不算祖先召回，不在该列表中。
 *
 * <p>{@code unavailableDueToRecalledAncestor} 为兼容单父血缘的单数视图：
 * 存在召回祖先时取升序列表的第一个，否则为 null；新调用方应使用复数字段。
 */
public record LineageEntryResponse(
        String batchKey,
        String batchNo,
        BatchStatus status,
        String unavailableDueToRecalledAncestor,
        List<String> unavailableDueToRecalledAncestors
) {
}
