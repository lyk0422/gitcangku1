package com.example.starter.repo;

/**
 * 应急走廊持久化记录。时间窗 [startUtc, endUtc) 左闭右开；
 * 高度窗 [altMin, altMax) 左闭右开，单位米。
 *
 * @param corridorId              走廊唯一标识
 * @param priority                走廊优先级（非负）；同优先级走廊不得时空高度重叠
 * @param xMin                    矩形左边界（含），米
 * @param yMin                    矩形下边界（含），米
 * @param xMax                    矩形右边界（含），米
 * @param yMax                    矩形上边界（含），米
 * @param startUtc                生效起点 epoch 毫秒（UTC，含）
 * @param endUtc                  生效终点 epoch 毫秒（UTC，不含）
 * @param altMin                  最低高度（含），米
 * @param altMax                  最高高度（不含），米
 * @param status                  ACTIVE / REVOKED
 * @param reason                  建立与让渡原因
 * @param createdBy               授权调度员标识
 * @param createdAirspaceVersion  走廊版本（创建生效时全局空域版本）
 * @param revokedAirspaceVersion  撤销生效版本；null 表示未撤销
 * @param createdAt               创建时间 epoch 毫秒（UTC）
 * @param revokedAt               撤销时间 epoch 毫秒（UTC）；null 表示未撤销
 */
public record CorridorPo(String corridorId, int priority,
                         int xMin, int yMin, int xMax, int yMax,
                         long startUtc, long endUtc, int altMin, int altMax,
                         String status, String reason, String createdBy,
                         long createdAirspaceVersion, Long revokedAirspaceVersion,
                         long createdAt, Long revokedAt) {
}
