package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 发布历史查询响应：按发布时刻升序、快照 id 升序稳定排列；只读，查询不修改状态。
 */
public record PublishHistoryResponse(List<PublishSnapshotView> snapshots) {
}
