package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 批量创建隔离令响应：返回实际建令数量与要求数量，及每道令的完整快照。
 *
 * @param createdCount  实际创建隔离令数量
 * @param requiredCount 要求创建数量（入参项数）
 * @param orders        各隔离令快照（按设备标识升序，稳定排序）
 */
public record BatchCreateIsolationResponse(
        int createdCount,
        int requiredCount,
        List<IsolationOrderResponse> orders) {
}
