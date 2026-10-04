package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 限速重排记录（不可变历史证据）。发布或改签因限速整体顺延成功时追加，
 * 固化当时使用的限速令版本与参数快照、原计划时刻与新计划时刻；
 * 后续撤销或修订限速令均不改写本记录。
 *
 * @param id                主键
 * @param planId            被重排计划 id，关联 rail_day_plan.id
 * @param scheduleKey       被重排计划业务键
 * @param restrictionKey    触发重排的限速令业务键
 * @param restrictionVersion 重排时限速令的版本快照
 * @param sectionId         受限区段 ID
 * @param maxSpeedKmh       重排时限速令的最高速度快照，单位 km/h
 * @param restrictionStartUtc 重排时限速时段开始快照（含），UTC
 * @param restrictionEndUtc   重排时限速时段结束快照（不含），UTC
 * @param seq               被顺延占用在计划内的序号
 * @param trainNo           列车编号
 * @param originalStartUtc  原计划占用开始（含），UTC
 * @param originalEndUtc    原计划占用结束（不含），UTC
 * @param shiftedStartUtc   新计划占用开始（含），UTC
 * @param shiftedEndUtc     新计划占用结束（不含），UTC
 * @param shiftMinutes      整体顺延分钟数（线路既有最小间隔的整数倍）
 * @param affectedMinutes   原运行时刻在限速时段内受影响的运行分钟数
 * @param createdAt         记录创建时刻，UTC 毫秒
 */
public record RearrangeRecord(long id, long planId, String scheduleKey,
                              String restrictionKey, int restrictionVersion,
                              String sectionId, int maxSpeedKmh,
                              Instant restrictionStartUtc, Instant restrictionEndUtc,
                              int seq, String trainNo,
                              Instant originalStartUtc, Instant originalEndUtc,
                              Instant shiftedStartUtc, Instant shiftedEndUtc,
                              int shiftMinutes, int affectedMinutes, long createdAt) {
}