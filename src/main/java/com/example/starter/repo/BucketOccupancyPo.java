package com.example.starter.repo;

/**
 * 时空桶占用行。
 *
 * @param clearanceId 持有占用的批件标识
 * @param routeId     航线标识
 * @param cellX       格网单元 X 下标
 * @param cellY       格网单元 Y 下标
 * @param timeBucket  时间桶下标（UTC）
 * @param priority    NORMAL / EMERGENCY
 */
public record BucketOccupancyPo(
        String clearanceId,
        String routeId,
        int cellX,
        int cellY,
        long timeBucket,
        String priority) {
}
