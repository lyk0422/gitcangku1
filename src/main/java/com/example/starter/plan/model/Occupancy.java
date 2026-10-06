package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 计划内一条区段占用，区间左闭右开 [startUtc, endUtc)。
 *
 * @param id        主键
 * @param planId    所属计划 id
 * @param seq       计划内序号，保持提交顺序
 * @param trainNo   列车编号
 * @param sectionId 区段 ID
 * @param startUtc  占用开始时刻（含），UTC
 * @param endUtc    占用结束时刻（不含），UTC，必须晚于 startUtc
 * @param powerKw   列车声明取电功率，单位千瓦，非负整数；未绑定变电所的区段不参与预算校验
 */
public record Occupancy(long id, long planId, int seq, String trainNo, String sectionId,
                        Instant startUtc, Instant endUtc, int powerKw) {
}
