package com.example.starter.repo;

/**
 * 审核快照中单个命中禁飞区的窗口信息。
 *
 * @param zoneId      命中的禁飞区标识
 * @param windowStart 审核时刻区域有效窗口开始，epoch 毫秒（UTC），区间含；null 表示全时
 * @param windowEnd   审核时刻区域有效窗口结束，epoch 毫秒（UTC），区间不含；null 表示全时
 */
public record ZoneHitPo(String zoneId, Long windowStart, Long windowEnd) {
}
