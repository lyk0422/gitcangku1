package com.example.starter.repo;

/**
 * 应急走廊持久化记录。
 *
 * @param corridorId   走廊唯一标识
 * @param xMin         覆盖矩形左边界（含），米
 * @param yMin         覆盖矩形下边界（含），米
 * @param xMax         覆盖矩形右边界（含），米
 * @param yMax         覆盖矩形上边界（含），米
 * @param startMs      有效期起点（含），epoch 毫秒 UTC
 * @param endMs        有效期终点（不含），epoch 毫秒 UTC
 * @param altMin       高度下界（含），米
 * @param altMax       高度上界（不含），米
 * @param priority     优先级，整数越大越高
 * @param status       ACTIVE / REVOKED
 * @param version      走廊版本，建立为 1，撤销加一
 * @param revokeReason 撤销原因；null 表示未撤销
 * @param revokedMs    撤销时刻 epoch 毫秒 UTC；null 表示未撤销
 * @param createdMs    建立记录时刻 epoch 毫秒 UTC
 */
public record CorridorPo(String corridorId, int xMin, int yMin, int xMax, int yMax,
                         long startMs, long endMs, int altMin, int altMax, int priority,
                         String status, int version, String revokeReason,
                         Long revokedMs, long createdMs) {
}
