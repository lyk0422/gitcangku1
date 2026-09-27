package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 计划为中间站登记的一条站停（草稿编辑态），到发区间左闭右开 [arrival, departure)。
 *
 * @param id           主键
 * @param planId       所属计划 id
 * @param seq          站停沿计划路径的序号，从 0 开始
 * @param trainNo      停靠列车编号
 * @param stationId    站点业务 ID
 * @param arrivalUtc   到达时刻（含），UTC；始发可为 null
 * @param departureUtc 发车时刻（不含），UTC；终到可为 null；两端齐全时必须晚于到达
 */
public record StationStop(long id, long planId, int seq, String trainNo, String stationId,
                          Instant arrivalUtc, Instant departureUtc) {
}
