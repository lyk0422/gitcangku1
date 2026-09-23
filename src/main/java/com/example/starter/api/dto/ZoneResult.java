package com.example.starter.api.dto;

/**
 * 禁飞区创建/撤销结果。
 *
 * @param zoneId          禁飞区标识
 * @param status          操作后状态：ACTIVE / REVOKED
 * @param airspaceVersion 操作后生效的全局空域版本
 * @param window          区域有效窗口（UTC 毫秒，左闭右开）；全时时刻均为 null
 */
public record ZoneResult(String zoneId, String status, long airspaceVersion,
                         TimeWindowDto window) {
}
