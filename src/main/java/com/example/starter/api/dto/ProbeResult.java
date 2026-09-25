package com.example.starter.api.dto;

/**
 * 可预约探测结果。只读判断给定时间窗在当前容量下能否再放入一个预约，
 * 不建立任何预约、不改变状态。
 *
 * @param corridorId 走廊标识
 * @param startTime  探测窗开始时刻，epoch 毫秒（UTC），含
 * @param endTime    探测窗结束时刻，epoch 毫秒（UTC），不含
 * @param capacity   走廊当前容量上限
 * @param peakOccupancy 探测窗内任意时刻的最大重叠生效预约数
 * @param available  true 表示窗内占用始终未达容量上限，可预约
 */
public record ProbeResult(String corridorId, long startTime, long endTime, int capacity,
                          int peakOccupancy, boolean available) {
}
