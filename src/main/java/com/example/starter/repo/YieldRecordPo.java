package com.example.starter.repo;

/**
 * 让渡不可变历史记录。
 *
 * @param seq               自增序号
 * @param occupancyId       被让渡的常规占用标识
 * @param corridorId        受让渡的走廊标识
 * @param corridorVersion   让渡时固化的走廊版本
 * @param reviewId          让渡时固化的原审查标识
 * @param routeVersion      让渡时固化的原航线版本
 * @param airspaceVersion   让渡时固化的原空域版本
 * @param reason            让渡原因
 * @param createdAt         让渡时间 epoch 毫秒（UTC）
 */
public record YieldRecordPo(long seq, String occupancyId, String corridorId,
                            long corridorVersion, String reviewId, int routeVersion,
                            long airspaceVersion, String reason, long createdAt) {
}
