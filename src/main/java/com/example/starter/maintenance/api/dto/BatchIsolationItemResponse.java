package com.example.starter.maintenance.api.dto;

/**
 * 批量隔离结果中的单项视图。
 *
 * @param equipmentId  被隔离设备标识
 * @param orderId      该设备隔离令标识
 * @param status       隔离令初始状态（PENDING）
 * @param lockPointCount 锁定点数量（要求值）
 */
public record BatchIsolationItemResponse(
        String equipmentId,
        String orderId,
        String status,
        int lockPointCount) {
}
