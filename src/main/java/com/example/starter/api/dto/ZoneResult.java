package com.example.starter.api.dto;

/**
 * 禁飞区创建/撤销结果。
 *
 * @param zoneId          禁飞区标识
 * @param status          操作后状态：ACTIVE / REVOKED
 * @param airspaceVersion 操作后生效的全局空域版本
 * @param windowStart     区域有效窗口开始时刻，epoch 毫秒（UTC），区间含；null 表示全时有效
 * @param windowEnd       区域有效窗口结束时刻，epoch 毫秒（UTC），区间不含；null 表示全时有效
 */
public record ZoneResult(String zoneId, String status, long airspaceVersion,
                         Long windowStart, Long windowEnd) {
}
