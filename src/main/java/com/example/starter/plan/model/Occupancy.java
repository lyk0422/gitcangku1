package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 计划内一条占用，区间左闭右开 [startUtc, endUtc)。
 *
 * <p>无编组的计划仅区段占用：{@code platformId} 为空；
 * 含编组的计划每行表示一次到发停靠，{@code platformId} 为停靠站台，
 * 发布时同步登记到 rail_platform_booking。
 *
 * @param id         主键
 * @param planId     所属计划 id
 * @param seq        计划内序号，保持提交顺序
 * @param trainNo    列车编号
 * @param sectionId  区段 ID
 * @param platformId 到发停靠站台 ID，无编组时为空
 * @param startUtc   占用开始时刻（含），UTC
 * @param endUtc     占用结束时刻（不含），UTC，必须晚于 startUtc
 */
public record Occupancy(long id, long planId, int seq, String trainNo, String sectionId,
                        String platformId, Instant startUtc, Instant endUtc) {
}
