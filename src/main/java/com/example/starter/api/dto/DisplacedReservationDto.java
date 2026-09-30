package com.example.starter.api.dto;

/**
 * 被挤出预留清单条目（不可变替换链的一环）。
 *
 * @param reservationId 被挤出的预留标识
 * @param routeId       被挤出的航线标识
 * @param priority      被挤出预留的优先级，数值越大越高
 * @param replacedBy    挤出它的新预留标识
 */
public record DisplacedReservationDto(String reservationId, String routeId, int priority,
                                      String replacedBy) {
}
