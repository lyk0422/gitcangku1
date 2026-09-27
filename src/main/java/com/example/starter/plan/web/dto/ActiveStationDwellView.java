package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 站台诊断中的当前生效站停视图（其他已发布计划占用），区间左闭右开。
 */
public record ActiveStationDwellView(String scheduleKey, String trainNo, String stationId,
                                     Instant arrivalUtc, Instant departureUtc,
                                     int actualDwellMinutes) {
}
