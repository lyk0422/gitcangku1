package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 交换单不可变快照中的一条占用（交换前实际占用或交换后目标占用）。
 *
 * @param id        主键
 * @param swapId    所属交换单 id
 * @param planId    快照所属计划 id
 * @param itemSeq   交换项稳定排序序号
 * @param phase     快照阶段：BEFORE / AFTER
 * @param occSeq    占用在该项该阶段内的稳定排序序号，从 0 开始
 * @param trainNo   列车编号
 * @param sectionId 区段 ID
 * @param startUtc  占用开始时刻（含），UTC
 * @param endUtc    占用结束时刻（不含），UTC
 */
public record CapacitySwapSnapshot(long id, long swapId, long planId, int itemSeq,
                                   CapacitySwapPhase phase, int occSeq, String trainNo,
                                   String sectionId, Instant startUtc, Instant endUtc) {
}
