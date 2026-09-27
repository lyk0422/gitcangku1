package com.example.starter.api.dto;

/**
 * 走廊查询结果。时间均为 epoch 毫秒（UTC），区间左闭右开。
 *
 * @param corridorId              走廊标识
 * @param priority                优先级
 * @param status                  ACTIVE / REVOKED
 * @param effective               查询时刻是否有效：status=ACTIVE 且 startUtc ≤ now &lt; endUtc
 * @param xMin                    矩形左边界（含），米
 * @param yMin                    矩形下边界（含），米
 * @param xMax                    矩形右边界（含），米
 * @param yMax                    矩形上边界（含），米
 * @param startUtc                生效起点（含）
 * @param endUtc                  生效终点（不含）
 * @param altMin                  最低高度（含），米
 * @param altMax                  最高高度（不含），米
 * @param reason                  建立与让渡原因
 * @param createdBy               授权调度员标识
 * @param createdAirspaceVersion  走廊版本（创建生效时全局空域版本）
 * @param revokedAirspaceVersion  撤销生效版本；null 表示未撤销
 * @param createdAt               创建时间
 * @param revokedAt               撤销时间；null 表示未撤销
 */
public record CorridorDto(String corridorId, int priority, String status, boolean effective,
                          int xMin, int yMin, int xMax, int yMax,
                          long startUtc, long endUtc, int altMin, int altMax,
                          String reason, String createdBy,
                          long createdAirspaceVersion, Long revokedAirspaceVersion,
                          long createdAt, Long revokedAt) {
}
