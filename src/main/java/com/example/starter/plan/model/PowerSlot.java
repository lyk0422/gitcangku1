package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 参与功率预算累计的一条占用时隙（已发布或候选），区间左闭右开。
 *
 * @param sectionId 区段 ID
 * @param startUtc  占用开始（含），UTC
 * @param endUtc    占用结束（不含），UTC
 * @param powerKw   声明取电功率，单位千瓦，非负整数
 */
public record PowerSlot(String sectionId, Instant startUtc, Instant endUtc, int powerKw) {
}
