package com.example.starter.plan.web.dto;

/**
 * 站台配置当前版本视图。时长单位分钟，容量单位个；operator 为 null 表示未登记操作者。
 */
public record StationConfigView(String stationId, int minDwellMinutes, int platformCapacity,
                                int version, String operator) {
}
