package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 发布固化的站停快照视图，插入后不可变；即使计划随后取消或改签，快照字段仍原样返回。
 *
 * @param scheduleKey       发布时所属计划业务键
 * @param seq               站停序号
 * @param trainNo           停靠列车编号
 * @param stationId         站点业务 ID
 * @param stationVersion    发布当时的站点配置版本
 * @param arrivalUtc        到达时刻（含），UTC；null 表示无到达（始发）
 * @param departureUtc      发车时刻（不含），UTC；null 表示无发车（终到）
 * @param minDwellMinutes   发布当时要求的最小停站分钟数
 * @param platformCapacity  发布当时的站台容量
 */
public record StationSnapshotView(String scheduleKey, int seq, String trainNo, String stationId,
                                  int stationVersion, Instant arrivalUtc, Instant departureUtc,
                                  int minDwellMinutes, int platformCapacity) {
}
