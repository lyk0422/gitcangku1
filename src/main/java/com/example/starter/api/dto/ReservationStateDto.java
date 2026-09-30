package com.example.starter.api.dto;

/**
 * 预留起飞登记结果。
 *
 * @param reservationId 预留唯一标识
 * @param airportId     备降机场标识
 * @param routeId       预留所属航线标识
 * @param status        起飞时状态，固定为 ACTIVE
 * @param departed      是否已起飞，固定为 true
 */
public record ReservationStateDto(
        String reservationId,
        String airportId,
        String routeId,
        String status,
        boolean departed) {
}
