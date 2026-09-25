package com.example.starter.api.dto;

import java.util.List;

/**
 * 按时段占用查询结果。返回与查询时段重叠的全部占用记录（含已取消历史）。
 *
 * @param zoneId      区域标识
 * @param bandId      高度带标识；null 表示查询该区域全部高度带
 * @param fromAt      查询时段起始，epoch 毫秒（UTC，含）
 * @param toAt        查询时段结束，epoch 毫秒（UTC，不含）
 * @param occupancies 重叠的占用记录（按创建时间排序）
 */
public record OccupancyListResult(
        String zoneId,
        String bandId,
        long fromAt,
        long toAt,
        List<OccupancyResult> occupancies) {
}
