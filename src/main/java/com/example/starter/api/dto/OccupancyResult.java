package com.example.starter.api.dto;

/**
 * 高度层占用结果。
 *
 * @param occupancyId 占用记录标识
 * @param reviewId    关联审查标识
 * @param routeId     航线标识
 * @param zoneId      区域标识
 * @param bandLower   高度带下限（含），米
 * @param bandUpper   高度带上限（不含），米（创建时快照，不随后续改带改写）
 * @param startTime   UTC 时段起始，epoch 毫秒（含）
 * @param endTime     UTC 时段结束，epoch 毫秒（不含）
 * @param status      ACTIVE / CANCELLED
 * @param capacity    该高度带当前容量
 * @param activeCount 创建时该带与本时段重叠的 ACTIVE 占用数（含本条）
 */
public record OccupancyResult(
        String occupancyId,
        String reviewId,
        String routeId,
        String zoneId,
        int bandLower,
        int bandUpper,
        long startTime,
        long endTime,
        String status,
        int capacity,
        int activeCount) {
}
