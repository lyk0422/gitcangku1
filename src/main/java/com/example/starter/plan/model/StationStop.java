package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 计划登记的一条中间站站停时刻。到达/发车间隔解释为停靠区间 [arrivalUtc, departureUtc)；
 * 允许单端为 {@code null}：仅发车表示始发，仅到达表示终到，二者皆空视为非法登记。
 *
 * @param id           主键
 * @param planId       所属计划 id
 * @param seq          计划内序号，从 0 开始，保持提交顺序与稳定排序
 * @param trainNo      站停所属列车编号
 * @param stationId    站点业务键
 * @param arrivalUtc   到达时刻（含），UTC；null 表示未登记到达（始发站）
 * @param departureUtc 发车时刻（不含），UTC；null 表示未登记发车（终到站）；
 *                     与到达同时存在时必须严格晚于到达
 */
public record StationStop(long id, long planId, int seq, String trainNo, String stationId,
                          Instant arrivalUtc, Instant departureUtc) {
}
