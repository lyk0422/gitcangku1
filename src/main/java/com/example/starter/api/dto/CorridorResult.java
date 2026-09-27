package com.example.starter.api.dto;

import java.util.List;

/**
 * 应急走廊建立结果。
 *
 * @param corridorId       走廊标识
 * @param status           建立后状态：ACTIVE
 * @param version          走廊版本（建立为 1）
 * @param startMs          有效期起点（含），epoch 毫秒 UTC
 * @param endMs            有效期终点（不含），epoch 毫秒 UTC
 * @param durationMs       要求的有效期时长（endMs - startMs，实际值），毫秒
 * @param yieldedOccupancies 本次事务内实际让渡的占用标识列表（稳定排序）
 */
public record CorridorResult(String corridorId, String status, int version,
                             long startMs, long endMs, long durationMs,
                             List<String> yieldedOccupancies) {
}
