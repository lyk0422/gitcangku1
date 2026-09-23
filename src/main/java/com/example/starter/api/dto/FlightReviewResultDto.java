package com.example.starter.api.dto;

import java.util.List;

/**
 * 带豁免核销的飞行审核结果（不可变快照的传输形态）。
 *
 * @param reviewId        审核快照唯一标识
 * @param flightKey       飞行审核标识
 * @param routeId         航线标识
 * @param routeVersion    冻结的航线版本
 * @param airspaceVersion 冻结的全局空域版本（空域版本）
 * @param permitKey       CLEAR 核销使用的豁免包标识；无命中或 BLOCKED 时为 null
 * @param permitVersion   冻结的豁免包版本；未使用豁免时为 null
 * @param reviewAt        审核指定的 UTC 时刻，epoch 毫秒
 * @param conclusion      CLEAR / BLOCKED
 * @param hitRegionKeys   几何命中的全部区域标识（字典序去重）
 * @param defects         BLOCKED 时各命中区域的缺失/版本不匹配/撤销/过期/耗尽缺陷；CLEAR 为空
 * @param redeems         CLEAR 且使用豁免时每项扣 1 的核销前后余额；其余情况为空
 * @param pointsSnapshot  审核时航点不可变快照
 */
public record FlightReviewResultDto(
        String reviewId,
        String flightKey,
        String routeId,
        int routeVersion,
        long airspaceVersion,
        String permitKey,
        Integer permitVersion,
        long reviewAt,
        String conclusion,
        List<String> hitRegionKeys,
        List<RegionDefectDto> defects,
        List<RedeemResultDto> redeems,
        List<RoutePointDto> pointsSnapshot) {
}
