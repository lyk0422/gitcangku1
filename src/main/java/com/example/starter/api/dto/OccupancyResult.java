package com.example.starter.api.dto;

import java.util.List;

/**
 * 占用登记/重新申请结果。
 *
 * @param occupancyId       占用标识（重新申请时为新占用标识）
 * @param routeId           航线标识
 * @param reviewId          依据的审查标识
 * @param kind              REGULAR / EMERGENCY
 * @param status            ACTIVE / YIELDED
 * @param startUtc          占用起点 epoch 毫秒（UTC，含）
 * @param endUtc            占用终点 epoch 毫秒（UTC，不含）
 * @param durationMillis    要求时长（毫秒）= endUtc - startUtc
 * @param altMin            最低高度（含），米
 * @param altMax            最高高度（不含），米
 * @param altitudeSpanMeters 要求高度跨度（米）= altMax - altMin
 * @param yieldable         常规占用是否可让渡；应急占用为 null（不适用）
 * @param departed          是否已起飞
 * @param corridorId        让渡走廊标识；未让渡为 null
 * @param parentOccupancyId 重新申请时原占用标识；原始登记为 null
 * @param pointsSnapshot    审查航点不可变快照
 */
public record OccupancyResult(String occupancyId, String routeId, String reviewId,
                              String kind, String status,
                              long startUtc, long endUtc, long durationMillis,
                              int altMin, int altMax, int altitudeSpanMeters,
                              Boolean yieldable, boolean departed,
                              String corridorId, String parentOccupancyId,
                              List<RoutePointDto> pointsSnapshot) {
}
