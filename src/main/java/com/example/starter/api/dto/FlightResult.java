package com.example.starter.api.dto;

/**
 * 航班结果。
 *
 * @param flightId    航班唯一标识
 * @param routeId     关联航线标识
 * @param routeType   航线类型：NORMAL / EMERGENCY
 * @param eventNo     紧急事件号；未附为 null
 * @param depRunwayId 起飞跑道标识
 * @param depTimeUtc  计划起飞时刻，epoch 毫秒（UTC）
 * @param arrRunwayId 落地跑道标识
 * @param arrTimeUtc  计划落地时刻，epoch 毫秒（UTC）
 * @param status      状态：PENDING/APPROVED/RUNWAY_RISK/DEPARTED/CANCELLED
 */
public record FlightResult(String flightId, String routeId, String routeType, String eventNo,
                           String depRunwayId, long depTimeUtc,
                           String arrRunwayId, long arrTimeUtc, String status) {
}
