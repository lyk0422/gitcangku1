package com.example.starter.api.dto;

import java.util.List;

/**
 * 空域占用明细。时间均为 UTC epoch 毫秒；时段与高度范围为左闭右开。
 *
 * @param occupancyId    占用唯一标识
 * @param routeId        占用航线标识
 * @param routeVersion   占用申请时的航线版本
 * @param routeType      航线类型：REGULAR / EMERGENCY
 * @param startAt        占用起点（含），epoch 毫秒（UTC）
 * @param endAt          占用终点（不含），epoch 毫秒（UTC）
 * @param altitudeLower  占用高度下限（含），单位米
 * @param altitudeUpper  占用高度上限（不含），单位米
 * @param yieldable      常规占用是否可被应急走廊强制让渡
 * @param status         ACTIVE / YIELDED
 * @param departed       是否已起飞登记
 * @param departedAt     起飞登记时间，epoch 毫秒（UTC）；未起飞为 null
 * @param reviewId       占用依据的 CLEAR 审核记录
 * @param pointsSnapshot 占用时航点不可变快照
 * @param createdAt      创建时间，epoch 毫秒（UTC）
 * @param durationMillis 占用时长（endAt - startAt），单位毫秒
 * @param yield          让渡记录；未被让渡为 null
 */
public record OccupancyDetailDto(
        String occupancyId,
        String routeId,
        int routeVersion,
        String routeType,
        long startAt,
        long endAt,
        int altitudeLower,
        int altitudeUpper,
        boolean yieldable,
        String status,
        boolean departed,
        Long departedAt,
        String reviewId,
        List<RoutePointDto> pointsSnapshot,
        long createdAt,
        long durationMillis,
        YieldRecordDto yield) {
}
