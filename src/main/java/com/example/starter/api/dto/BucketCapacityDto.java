package com.example.starter.api.dto;

/**
 * 桶容量余量明细（预览与证据查询共用）。
 *
 * @param cellX       网格单元 X 索引
 * @param cellY       网格单元 Y 索引
 * @param bucketStart 时间桶起始时刻，epoch 毫秒（UTC）
 * @param maxFlights  配置上限；未配置为 0
 * @param usedBefore  转配前全量占用数（含未参与航线）；预览时为当前占用
 * @param usedAfter   转配后全量占用数（含未参与航线）
 */
public record BucketCapacityDto(
        Integer cellX,
        Integer cellY,
        Long bucketStart,
        Integer maxFlights,
        Integer usedBefore,
        Integer usedAfter) {
}
