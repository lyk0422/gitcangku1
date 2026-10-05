package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 重排明细：逐段固化原计划时刻与新计划时刻，区间左闭右开，追加后不可变。
 *
 * @param id              主键
 * @param rearrangementId 所属重排记录 id
 * @param seq             占用在计划内的序号，与计划占用一致
 * @param trainNo         列车编号
 * @param sectionId       区段 ID
 * @param origStartUtc    原计划开始时刻（含），UTC
 * @param origEndUtc      原计划结束时刻（不含），UTC
 * @param newStartUtc     新计划开始时刻（含），UTC
 * @param newEndUtc       新计划结束时刻（不含），UTC
 */
public record RearrangementItem(long id, long rearrangementId, int seq, String trainNo,
                                String sectionId, Instant origStartUtc, Instant origEndUtc,
                                Instant newStartUtc, Instant newEndUtc) {
}
