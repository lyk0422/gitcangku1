package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 其他已发布计划在指定站点的一条生效站停（诊断查询用）。
 *
 * @param scheduleKey  所属计划业务键
 * @param trainNo      列车编号
 * @param stationId    站点 ID
 * @param arrivalUtc   到达时刻（含），UTC
 * @param departureUtc 发车时刻（不含），UTC
 */
public record PublishedDwell(String scheduleKey, String trainNo, String stationId,
                             Instant arrivalUtc, Instant departureUtc) {
}
