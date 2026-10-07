package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 计划占用的实际进出视图，计划时隙与实际时刻分列，区间左闭右开 [startUtc, endUtc)。
 *
 * @param id              占用主键
 * @param planId          所属计划 id
 * @param seq             计划内占用序号
 * @param trainNo         列车编号
 * @param sectionId       区段 ID
 * @param startUtc        计划占用开始时刻（含），UTC
 * @param endUtc          计划占用结束时刻（不含），UTC
 * @param actualStatus    实际状态
 * @param enteredAt       实际进入时刻（服务端 UTC），未进入为 null
 * @param exitedAt        实际驶出时刻（服务端 UTC），未驶出为 null
 * @param exitPlanVersion 驶出时原计划版本，未驶出为 null
 */
public record ActualOccupancy(long id, long planId, int seq, String trainNo, String sectionId,
                              Instant startUtc, Instant endUtc, ActualStatus actualStatus,
                              Instant enteredAt, Instant exitedAt, Integer exitPlanVersion) {
}
