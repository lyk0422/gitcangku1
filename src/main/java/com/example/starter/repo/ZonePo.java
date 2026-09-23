package com.example.starter.repo;

/**
 * 禁飞区持久化记录。
 *
 * @param zoneId         禁飞区唯一标识
 * @param xMin           左边界（含），米
 * @param yMin           下边界（含），米
 * @param xMax           右边界（含），米
 * @param yMax           上边界（含），米
 * @param status         ACTIVE / REVOKED
 * @param createdVersion 创建后生效的全局空域版本
 * @param revokedVersion 撤销后生效的全局空域版本；null 表示仍有效
 * @param windowStart    区域有效窗口开始时刻，epoch 毫秒（UTC），区间含；null 与 windowEnd 同时为 null 表示全时有效
 * @param windowEnd      区域有效窗口结束时刻，epoch 毫秒（UTC），区间不含；非全时时严格晚于 windowStart
 */
public record ZonePo(String zoneId, int xMin, int yMin, int xMax, int yMax,
                     String status, long createdVersion, Long revokedVersion,
                     Long windowStart, Long windowEnd) {
}
