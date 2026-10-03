package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 逐人资格覆盖结果：发布时依据的资格版本与有效区间、计划占用窗口，
 * 以及当前与该成员占用窗口重叠的其他已发布计划。
 *
 * @param crewMemberId            乘务员 ID
 * @param qualificationId         发布时依据的资格 id
 * @param qualificationVersion    发布时依据的资格版本（历史发布保留原版本）
 * @param lineGrade               发布时资格线路等级快照
 * @param validFromUtc            资格生效时刻快照（含），UTC
 * @param validToUtc              资格失效时刻快照（不含），UTC
 * @param tripStartUtc            计划占用窗口开始（含），UTC
 * @param tripEndUtc              计划占用窗口结束（不含），UTC
 * @param covered                 发布时资格区间是否完整覆盖行程且等级达标（快照结果）
 * @param conflictingScheduleKeys 当前占用窗口重叠的其他已发布计划，按业务键升序
 */
public record CrewCheckItem(String crewMemberId, long qualificationId, int qualificationVersion,
                            int lineGrade, Instant validFromUtc, Instant validToUtc,
                            Instant tripStartUtc, Instant tripEndUtc, boolean covered,
                            List<String> conflictingScheduleKeys) {
}