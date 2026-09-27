package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量隔离多设备响应：整批在单事务提交，要么全部生效要么全部不生效。
 *
 * @param requestId     创建请求 requestId
 * @param createdBy     创建操作者
 * @param createdAt     批量创建时刻（UTC）
 * @param orderCount    批内隔离令数量（要求值 = 实际值）
 * @param equipmentCount 批内设备数量（要求值 = 实际值）
 * @param orders        各设备隔离令视图（按请求提交顺序稳定排序）
 */
public record BatchIsolationResponse(
        String requestId,
        String createdBy,
        Instant createdAt,
        int orderCount,
        int equipmentCount,
        List<BatchIsolationItemResponse> orders) {
}
