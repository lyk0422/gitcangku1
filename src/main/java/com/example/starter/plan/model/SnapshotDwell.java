package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 发布快照中固化的一条站停：发布时刻的到发时刻、站台配置版本/规则值与实际停留分钟。
 * 写入后不可变；站台配置后续修订对本行无影响。
 *
 * @param snapshotId         所属发布快照 id
 * @param seq                快照内序号，从 0 开始
 * @param trainNo            停靠列车编号
 * @param stationId          中间站站点 ID
 * @param arrivalUtc         到达时刻（含），UTC
 * @param departureUtc       发车时刻（不含），UTC
 * @param stationVersion     发布时该站站台配置版本
 * @param minDwellMinutes    发布时固化的最小停站分钟数下限，单位分钟
 * @param platformCapacity   发布时固化的站台容量，单位个
 * @param actualDwellMinutes 实际停留分钟数 = (发车-到达)/60000
 */
public record SnapshotDwell(long snapshotId, int seq, String trainNo, String stationId,
                            Instant arrivalUtc, Instant departureUtc, int stationVersion,
                            int minDwellMinutes, int platformCapacity, int actualDwellMinutes) {
}
