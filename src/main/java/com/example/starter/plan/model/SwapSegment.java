package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 预览时冻结的提交占用段（不含列车编号）。
 *
 * @param swapId    所属交换单 id
 * @param itemSeq   交换项稳定排序序号
 * @param phase     阶段：BEFORE 提交的当前占用 / AFTER 提交的目标占用
 * @param occSeq    占用段规范排序序号，从 0 开始
 * @param sectionId 区段 ID
 * @param startUtc  占用开始时刻（含），UTC
 * @param endUtc    占用结束时刻（不含），UTC
 */
public record SwapSegment(long swapId, int itemSeq, CapacitySwapPhase phase, int occSeq,
                          String sectionId, Instant startUtc, Instant endUtc) {
}
