package com.example.starter.plan.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 指定运营日某站点上当前生效（计划状态 PUBLISHED）的一条站停快照，
 * 用于跨计划站台容量裁决与诊断查询；携带发布时固化的规则与站点版本。
 *
 * @param planId           所属计划 id
 * @param scheduleKey      所属计划业务键
 * @param opDate           运营日期
 * @param seq              计划内序号
 * @param trainNo          列车编号
 * @param stationId        站点业务键
 * @param arrivalUtc       到达时刻（含），UTC；可为 null（始发）
 * @param departureUtc     发车时刻（不含），UTC；可为 null（终到）
 * @param stationVersion   发布时刻固化的站点配置版本
 * @param minDwellMinutes  发布时刻固化的最小停站分钟数
 * @param platformCapacity 发布时刻固化的站台容量
 */
public record EffectiveStationStop(long planId, String scheduleKey, LocalDate opDate, int seq,
                                   String trainNo, String stationId,
                                   Instant arrivalUtc, Instant departureUtc,
                                   int stationVersion, int minDwellMinutes,
                                   int platformCapacity) {
}
