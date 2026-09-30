package com.example.starter.api.dto;

import java.util.List;

/**
 * 机场时段占用视图。按机场与 UTC 左闭右开查询时段返回当前占用、优先级与
 * 不可变替换链。仅返回与查询时段有重叠的预留。
 *
 * @param airportId 机场标识
 * @param capacity  机场窗口容量
 * @param from      查询时段开始（含），epoch 毫秒（UTC）
 * @param to        查询时段结束（不含），epoch 毫秒（UTC）
 * @param occupancy 与查询时段重叠的占用条目，按窗口开始、预留标识排序
 */
public record OccupancyView(String airportId, int capacity, long from, long to,
                            List<OccupancyEntry> occupancy) {
}
