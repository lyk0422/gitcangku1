package com.example.starter.batch.dto;

import java.util.List;

/**
 * 血缘查询中的单个批次节点：批次自身概要（含自身状态）及导致其不可用的召回祖先列表（无则为空）。
 */
public record LineageNodeResponse(
        BatchResponse batch,
        List<RecalledAncestorResponse> recalledAncestors
) {
}
