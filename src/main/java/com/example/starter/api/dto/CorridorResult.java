package com.example.starter.api.dto;

import java.util.List;

/**
 * 应急走廊创建/撤销结果。
 *
 * @param corridorId            走廊标识
 * @param status                操作后状态：ACTIVE / REVOKED
 * @param version               操作后走廊版本（创建为 1，撤销后加一）
 * @param yieldedCount          本次创建强制让渡的常规占用数量（实际值；撤销时恒为 0）
 * @param yieldedOccupationIds  被让渡的占用标识列表（字典序；撤销时为空列表）
 */
public record CorridorResult(String corridorId, String status, int version,
                             int yieldedCount, List<String> yieldedOccupationIds) {
}
