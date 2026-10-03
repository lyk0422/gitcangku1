package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 计划重排记录（不可变）：发布或改签因限速整体顺延时追加，
 * 固化裁决使用的限速令版本快照、原计划时刻与新计划时刻；
 * 后续撤销或修订限速令不得改写本记录。
 *
 * @param id                   主键，历史查询按 id 升序
 * @param planId               被重排的计划 id
 * @param scheduleKey          被重排的计划业务键
 * @param opDate               计划运营日期（Asia/Shanghai 日历日）
 * @param shiftMinutes         整体顺延分钟数，为线路最小间隔的整数倍，大于 0
 * @param restrictionSnapshot  裁决时限速令快照 JSON（业务键、版本、区段、时段、最高速度）
 * @param originalOccupancies  重排前原计划占用时刻 JSON（UTC ISO-8601，毫秒精度）
 * @param newOccupancies       重排后新计划占用时刻 JSON（UTC ISO-8601，毫秒精度）
 * @param operator             触发重排的操作者标识，null 表示调用方未提供
 * @param createdAt            记录创建时刻，UTC 毫秒
 */
public record RearrangeRecord(long id, long planId, String scheduleKey, LocalDate opDate,
                              int shiftMinutes, String restrictionSnapshot,
                              String originalOccupancies, String newOccupancies,
                              String operator, long createdAt) {
}