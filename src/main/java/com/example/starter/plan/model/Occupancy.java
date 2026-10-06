package com.example.starter.plan.model;

import java.math.BigDecimal;
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
 * @param speedMps  占用速度 v，米每秒，非负、最多3位小数；
 *                  null 表示原无速度占用，不适用应急制动包络判定
 */
public record Occupancy(long id, long planId, int seq, String trainNo, String sectionId,
                        Instant startUtc, Instant endUtc, BigDecimal speedMps) {
}
