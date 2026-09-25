package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 抢占涉及的时隙（不可变），用于“同一时隙只能被抢占一次”的判定。
 *
 * @param id           主键
 * @param preemptionId 所属抢占记录 id
 * @param sectionId    涉及区段 ID
 * @param sectionLevel 该区段登记等级（抢占时快照）
 * @param startUtc     被抢占时隙开始（含），UTC
 * @param endUtc       被抢占时隙结束（不含），UTC
 */
public record PreemptionSlot(long id, long preemptionId, String sectionId, int sectionLevel,
                             Instant startUtc, Instant endUtc) {
}
