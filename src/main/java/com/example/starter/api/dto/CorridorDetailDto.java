package com.example.starter.api.dto;

/**
 * 应急走廊明细。时间均为 UTC epoch 毫秒；时段与高度范围为左闭右开。
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
 * @param effective      查询时刻是否实际生效（ACTIVE 且当前时刻处于生效时间窗内）
 * @param dispatcherId   创建走廊的授权调度员标识
 * @param reason         预留原因
 * @param createdVersion 创建生效时的全局空域版本（走廊版本）
 * @param revokedVersion 撤销生效时的全局空域版本；未撤销为 null
 * @param createdAt      创建时间，epoch 毫秒（UTC）
 * @param revokedAt      撤销时间，epoch 毫秒（UTC）；未撤销为 null
 * @param durationMillis 走廊有效期时长（endAt - startAt），单位毫秒
 */
public record CorridorDetailDto(
        String corridorId,
        int xMin, int yMin, int xMax, int yMax,
        long startAt, long endAt,
        int altitudeLower, int altitudeUpper,
        int priority,
        String status,
        boolean effective,
        String dispatcherId,
        String reason,
        long createdVersion,
        Long revokedVersion,
        long createdAt,
        Long revokedAt,
        long durationMillis) {
}
