package com.example.starter.api.dto;

/**
 * 审核命中禁飞区的快照项：zoneId 与其审核时刻的有效窗口。
 *
 * @param zoneId      命中的禁飞区标识
 * @param windowStart 区域有效窗口开始时刻，epoch 毫秒（UTC），区间含；null 表示全时有效
 * @param windowEnd   区域有效窗口结束时刻，epoch 毫秒（UTC），区间不含；null 表示全时有效
 */
public record ZoneHitDto(String zoneId, Long windowStart, Long windowEnd) {
}
