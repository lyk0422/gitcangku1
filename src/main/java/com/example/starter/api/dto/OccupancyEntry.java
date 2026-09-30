package com.example.starter.api.dto;

import java.util.List;

/**
 * 占用视图中的单条预留。
 *
 * @param reservationId 预留标识
 * @param routeId       航线标识
 * @param priority      优先级，数值越大越高
 * @param windowStart   UTC 窗口开始（含），epoch 毫秒
 * @param windowEnd     UTC 窗口结束（不含），epoch 毫秒
 * @param status        RESERVED / DEPARTED / DISPLACED
 * @param replacedBy    挤出本预留的预留标识；从未被挤出为 null（不可变）
 * @param chain         不可变替换链：从本预留开始，每项为挤出它的预留，按替换先后排序；
 *                      末端预留的 replacedBy 为 null。后续起飞不改写旧替换结果。
 */
public record OccupancyEntry(String reservationId, String routeId, int priority,
                             long windowStart, long windowEnd, String status, String replacedBy,
                             List<ReplacementLink> chain) {
}
