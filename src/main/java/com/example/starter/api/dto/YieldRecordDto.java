package com.example.starter.api.dto;

/**
 * 让渡不可变历史条目，可关联原占用、走廊版本与处置结果。
 *
 * @param seq               自增序号（稳定排序）
 * @param occupancyId       被让渡的原占用标识
 * @param corridorId        走廊标识
 * @param corridorVersion   固化的走廊版本
 * @param reviewId          固化的原审查标识
 * @param routeVersion      固化的原航线版本
 * @param airspaceVersion   固化的原空域版本
 * @param reason            让渡原因
 * @param createdAt         让渡时间 epoch 毫秒（UTC）
 */
public record YieldRecordDto(long seq, String occupancyId, String corridorId,
                             long corridorVersion, String reviewId, int routeVersion,
                             long airspaceVersion, String reason, long createdAt) {
}
