package com.example.starter.domain;

/**
 * 禁飞区有效窗口快照（审核时记录，不可变）。
 *
 * @param zoneId      禁飞区标识
 * @param windowStart 有效窗口起始时刻（UTC epoch 毫秒，左闭）；
 *                    与 windowEnd 成对为 null 表示全时有效
 * @param windowEnd   有效窗口结束时刻（UTC epoch 毫秒，右开）
 */
public record ZoneWindow(String zoneId, Long windowStart, Long windowEnd) {
}
