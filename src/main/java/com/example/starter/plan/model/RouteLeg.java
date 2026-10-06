package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 寻径发布计划冻结的路径分段，区间左闭右开 [startUtc, endUtc)。
 *
 * @param id          主键
 * @param planId      所属计划 id
 * @param seq         分段在路径内的序号，从 0 开始
 * @param segmentId   区段编号（发布时图版本内）
 * @param fromStation 分段起点站
 * @param toStation   分段终点站
 * @param startUtc    分段开始时刻（含），UTC
 * @param endUtc      分段结束时刻（不含），UTC
 */
public record RouteLeg(long id, long planId, int seq, String segmentId, String fromStation,
                       String toStation, Instant startUtc, Instant endUtc) {
}
