package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 抢占记录涉及的区段与时隙明细，用于"同一时隙只能被抢占一次"的判定。
 *
 * @param id           主键
 * @param recordId     所属抢占记录 id
 * @param sectionId    涉及区段 ID
 * @param sectionLevel 该区段登记等级（固化，未登记为 1）
 * @param startUtc     被抢占时隙开始（含），UTC
 * @param endUtc       被抢占时隙结束（不含），UTC
 */
public record PreemptionSection(long id, long recordId, String sectionId, int sectionLevel,
                                Instant startUtc, Instant endUtc) {
}
