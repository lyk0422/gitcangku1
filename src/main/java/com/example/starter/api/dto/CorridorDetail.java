package com.example.starter.api.dto;

/**
 * 应急走廊明细。时间与高度均为左闭右开区间，epoch 毫秒（UTC）/米。
 *
 * @param corridorId 走廊标识
 * @param status     ACTIVE / REVOKED
 * @param version    走廊版本
 * @param xMin       区域左边界（含），米
 * @param yMin       区域下边界（含），米
 * @param xMax       区域右边界（含），米
 * @param yMax       区域上边界（含），米
 * @param startUtc   生效起点（含），epoch 毫秒（UTC）
 * @param endUtc     生效终点（不含），epoch 毫秒（UTC）
 * @param altMin     高度下限（含），米
 * @param altMax     高度上限（不含），米
 * @param priority   走廊优先级
 * @param operator   创建调度员标识
 * @param createdAt  创建时间，epoch 毫秒（UTC）
 * @param revokedAt  撤销时间，epoch 毫秒（UTC）；未撤销为 null
 */
public record CorridorDetail(String corridorId, String status, int version,
                             int xMin, int yMin, int xMax, int yMax,
                             long startUtc, long endUtc, int altMin, int altMax,
                             int priority, String operator, long createdAt, Long revokedAt) {
}
