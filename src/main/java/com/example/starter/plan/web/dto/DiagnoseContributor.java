package com.example.starter.plan.web.dto;

/**
 * 限速诊断贡献项：一条与查询时段相交的生效限速令及其相交分钟数。
 */
public record DiagnoseContributor(String restrictionKey, int version, int maxSpeedKmh,
                                  long overlapMinutes) {
}
