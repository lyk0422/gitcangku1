package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 重排逐段明细：固化原计划时刻、新计划时刻与起决定作用（速度最低）的限速令版本，追加后不可变。
 *
 * @param id                 主键
 * @param rearrangementId    所属重排记录 id
 * @param seq                段在计划内的序号，与占用序号对齐
 * @param trainNo            列车编号
 * @param sectionId          区段 ID
 * @param oldStartUtc        原计划段开始时刻（含），UTC
 * @param oldEndUtc          原计划段结束时刻（不含），UTC
 * @param newStartUtc        重排后段开始时刻（含），UTC
 * @param newEndUtc          重排后段结束时刻（不含），UTC
 * @param affectedMinutes    该段与全部生效限速窗口交集的受影响运行分钟数（并集口径）
 * @param restrictionId      起决定作用的限速令 id
 * @param restrictionKey     起决定作用的限速令业务键（固化）
 * @param restrictionVersion 起决定作用的限速令版本（固化）
 * @param maxSpeedKmh        起决定作用的限速速度，单位 km/h（固化）
 */
public record RearrangementSegment(long id, long rearrangementId, int seq, String trainNo,
                                   String sectionId, Instant oldStartUtc, Instant oldEndUtc,
                                   Instant newStartUtc, Instant newEndUtc, long affectedMinutes,
                                   long restrictionId, String restrictionKey, int restrictionVersion,
                                   int maxSpeedKmh) {
}
