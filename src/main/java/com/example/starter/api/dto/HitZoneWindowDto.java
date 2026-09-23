package com.example.starter.api.dto;

/**
 * 命中区域在审核时的有效窗口快照（不可变）。
 *
 * @param zoneId 命中区域标识
 * @param window 审核时该区域的有效窗口（UTC 毫秒，左闭右开）；全时时刻均为 null
 */
public record HitZoneWindowDto(String zoneId, TimeWindowDto window) {
}
