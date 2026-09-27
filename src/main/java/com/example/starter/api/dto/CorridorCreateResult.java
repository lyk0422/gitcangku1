package com.example.starter.api.dto;

import java.util.List;

/**
 * 创建应急走廊结果。
 *
 * @param corridorId           走廊唯一标识
 * @param status               走廊状态：ACTIVE
 * @param airspaceVersion      创建生效后的全局空域版本（即走廊版本）
 * @param yieldedOccupancyIds  本次事务内被强制让渡的常规占用标识（按 occupancyId 排序）
 * @param yieldedCount         实际让渡占用数量（与 yieldedOccupancyIds 长度一致）
 * @param durationMillis       走廊有效期时长（endAt - startAt），单位毫秒
 */
public record CorridorCreateResult(
        String corridorId,
        String status,
        long airspaceVersion,
        List<String> yieldedOccupancyIds,
        int yieldedCount,
        long durationMillis) {
}
