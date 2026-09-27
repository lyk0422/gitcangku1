package com.example.starter.repo;

/**
 * 应急走廊记录。
 *
 * @param corridorId     走廊唯一标识
 * @param xMin           区域左边界（含），单位米
 * @param yMin           区域下边界（含），单位米
 * @param xMax           区域右边界（含），单位米
 * @param yMax           区域上边界（含），单位米
 * @param startAt        生效起点（含），epoch 毫秒（UTC）
 * @param endAt          生效终点（不含），epoch 毫秒（UTC）
 * @param altitudeLower  高度下限（含），单位米
 * @param altitudeUpper  高度上限（不含），单位米
 * @param priority       走廊优先级
 * @param status         ACTIVE / REVOKED
 * @param dispatcherId   创建走廊的授权调度员标识
 * @param reason         预留原因
 * @param createdVersion 创建生效时的全局空域版本（走廊版本）
 * @param revokedVersion 撤销生效时的全局空域版本；null 表示未撤销
 * @param createdAt      创建时间（epoch 毫秒）
 * @param revokedAt      撤销时间（epoch 毫秒）；null 表示未撤销
 */
public record CorridorPo(String corridorId, int xMin, int yMin, int xMax, int yMax,
                         long startAt, long endAt, int altitudeLower, int altitudeUpper,
                         int priority, String status, String dispatcherId, String reason,
                         long createdVersion, Long revokedVersion,
                         long createdAt, Long revokedAt) {
}
