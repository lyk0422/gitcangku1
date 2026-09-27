package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 计划为中间站登记的一条列车到发时刻，区间左闭右开 [arrivalUtc, departureUtc)。
 * 随草稿占用一起整体替换；发布后历史登记保留不改写。
 *
 * @param id           主键
 * @param planId       所属计划 id
 * @param seq          计划内序号，从 0 开始，保持提交顺序
 * @param trainNo      停靠列车编号
 * @param stationId    中间站站点 ID
 * @param arrivalUtc   到达时刻（含），UTC，整分钟
 * @param departureUtc 发车时刻（不含），UTC，整分钟；语义校验（晚于到达、满足停站下限）在发布时裁决
 */
public record StationDwell(long id, long planId, int seq, String trainNo, String stationId,
                           Instant arrivalUtc, Instant departureUtc) {
}
