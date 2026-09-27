package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 发布时固化的站停快照，插入后不可变：站点配置版本、规则值与到发时刻均不随后续修订改写。
 *
 * @param planId            发布计划 id
 * @param seq               站停沿计划路径的序号
 * @param trainNo           停靠列车编号
 * @param stationId         站点业务 ID
 * @param stationVersion    发布当时的站点配置版本
 * @param arrivalUtc        固化的到达时刻（含），UTC；null 表示无到达（始发）
 * @param departureUtc      固化的发车时刻（不含），UTC；null 表示无发车（终到）
 * @param minDwellMinutes   发布当时要求的最小停站分钟数
 * @param platformCapacity  发布当时的站台容量
 */
public record PublishedStationStop(long planId, int seq, String trainNo, String stationId,
                                   int stationVersion, Instant arrivalUtc, Instant departureUtc,
                                   int minDwellMinutes, int platformCapacity) {
}
