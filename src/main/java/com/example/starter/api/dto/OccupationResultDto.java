package com.example.starter.api.dto;

/**
 * 高度层占用结果/视图。时间窗为左闭右开，单位 epoch 毫秒（UTC）。
 *
 * @param occupationId   占用记录唯一标识
 * @param reviewId       关联审查记录标识
 * @param routeId        占用航线标识
 * @param zoneId         占用禁飞区标识
 * @param bandId         占用高度带标识
 * @param startUtc       占用 UTC 起始时刻（含）
 * @param endUtc         占用 UTC 结束时刻（不含）
 * @param cruiseAltitude 巡航高度快照（米）
 * @param status         ACTIVE / CANCELLED
 * @param createdAt      创建时间（epoch 毫秒，UTC）
 * @param cancelledAt    取消时间（epoch 毫秒，UTC）；未取消为 null
 */
public record OccupationResultDto(
        String occupationId,
        String reviewId,
        String routeId,
        String zoneId,
        String bandId,
        long startUtc,
        long endUtc,
        int cruiseAltitude,
        String status,
        long createdAt,
        Long cancelledAt) {
}
