package com.example.starter.batch.dto;

import java.util.List;

/**
 * 祖先/后代查询响应：self 为被查询批次节点；
 * ancestors 由近及远（直接父批在前，根祖先在后）；descendants 按层次（BFS）排列。
 */
public record LineageResponse(
        LineageNodeResponse self,
        List<LineageNodeResponse> ancestors,
        List<LineageNodeResponse> descendants
) {
}
