package com.example.starter.plan.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 发布时固化的站停快照（不可变）：到发时刻、站点配置版本与当时的停站规则。
 * 后续站点规则修订不改写本记录；计划取消后行保留，作为历史业务证据。
 *
 * @param id               主键
 * @param planId           所属计划 id
 * @param opDate           运营日期（Asia/Shanghai 日历日）
 * @param seq              计划内序号，稳定排序
 * @param trainNo          站停所属列车编号
 * @param stationId        站点业务键
 * @param arrivalUtc       固化的到达时刻（含），UTC；null 语义与草稿一致
 * @param departureUtc     固化的发车时刻（不含），UTC；null 语义与草稿一致
 * @param stationVersion   发布时刻站点配置版本
 * @param minDwellMinutes  发布时刻站点最小停站分钟数
 * @param platformCapacity 发布时刻站点站台容量
 * @param publishedAt      快照固化时刻，UTC 毫秒
 */
public record PublishedStop(long id, long planId, LocalDate opDate, int seq, String trainNo,
                            String stationId, Instant arrivalUtc, Instant departureUtc,
                            int stationVersion, int minDwellMinutes, int platformCapacity,
                            long publishedAt) {
}
