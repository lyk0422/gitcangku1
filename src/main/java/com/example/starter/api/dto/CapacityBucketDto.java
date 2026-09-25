package com.example.starter.api.dto;

/**
 * 容量桶占用行：一个批件在一个时空桶上的一条占用。
 *
 * @param clearanceId 持有占用的批件标识
 * @param routeId     航线标识
 * @param cellX       格网单元 X 下标
 * @param cellY       格网单元 Y 下标
 * @param timeBucket  10 分钟时间桶下标（UTC）
 * @param priority    占用优先级：NORMAL / EMERGENCY
 * @param clearanceStatus 批件当前状态：APPROVED / DEPARTED
 */
public record CapacityBucketDto(
        String clearanceId,
        String routeId,
        Integer cellX,
        Integer cellY,
        Long timeBucket,
        String priority,
        String clearanceStatus) {
}
