package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.util.List;

/**
 * 血缘查询条目：批次自身状态 + 导致其不可用的全部召回祖先业务键。
 *
 * <p>多父合批后血缘由树扩展为有向无环图，一个后代可能经多条路径继承多个已召回祖先，
 * recalledAncestors 按 batchKey 升序去重列出全部召回祖先；召回一条路径不能被另一条
 * 未召回路径抵消。为兼容单父时期字段保留 unavailableDueToRecalledAncestor：
 * 无召回祖先时为 null，否则取 recalledAncestors 排序后的第一个。
 * 批次自身被直接召回（RECALLED）不算祖先召回，两个字段均为空。
 */
public record LineageEntryResponse(
        String batchKey,
        String batchNo,
        BatchStatus status,
        String unavailableDueToRecalledAncestor,
        List<String> recalledAncestors
) {
}
