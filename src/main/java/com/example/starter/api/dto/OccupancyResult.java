package com.example.starter.api.dto;

/**
 * 高度层占用结果。
 *
 * @param occupancyId     占用记录唯一标识
 * @param reviewId        关联的审核记录标识
 * @param routeId         占用航线标识
 * @param zoneId          占用区域标识
 * @param bandId          占用高度带标识
 * @param cruiseAltitudeM 巡航高度快照，单位米
 * @param startAt         占用起始时刻，epoch 毫秒（UTC，含）
 * @param endAt           占用结束时刻，epoch 毫秒（UTC，不含）
 * @param consumes        巡航高度是否落入高度带（true 消耗该带容量）
 * @param status          ACTIVE / CANCELLED
 */
public record OccupancyResult(
        String occupancyId,
        String reviewId,
        String routeId,
        String zoneId,
        String bandId,
        int cruiseAltitudeM,
        long startAt,
        long endAt,
        boolean consumes,
        String status) {
}
