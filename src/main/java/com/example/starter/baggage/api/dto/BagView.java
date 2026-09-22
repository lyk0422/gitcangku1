package com.example.starter.baggage.api.dto;

/**
 * 行李视图。
 *
 * @param bagTag         行李牌号
 * @param status         状态：IN_TRANSIT / DELIVERED
 * @param currentStation 当前所在站
 * @param nextLegIndex   待乘航段索引（等于 itinerarySize 表示行程完成）
 * @param itinerarySize  行程航段总数
 * @param loadedLegId    当前已装载航段，未装载为 null
 */
public record BagView(String bagTag, String status, String currentStation,
                      int nextLegIndex, int itinerarySize, String loadedLegId) {
}
