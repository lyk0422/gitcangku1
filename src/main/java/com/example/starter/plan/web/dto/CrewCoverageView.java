package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 乘务门禁逐人校验结果（按 memberKey 升序稳定排列）。
 *
 * @param memberKey            乘务工号
 * @param memberName           乘务员姓名
 * @param qualificationVersion 当前 ACTIVE 资格版本号；无有效资格时为 null
 * @param gradeLevel           当前资格可值乘最高等级；无有效资格时为 null
 * @param validFromUtc         当前资格生效时刻（含），UTC；无有效资格时为 null
 * @param validToUtc           当前资格失效时刻（不含），UTC，null 表示长期有效或无资格
 * @param intervalCovered      资格区间是否完整覆盖计划行程 [tripStartUtc, tripEndUtc)（左闭右开）
 * @param gradeSufficient      资格等级是否不小于行程要求等级
 * @param occupied             该乘务员是否在重叠时间窗内已被其他已发布计划占用
 * @param windowStartUtc       该乘务员在本计划上的占用窗口起点（含），UTC
 * @param windowEndUtc         该乘务员在本计划上的占用窗口终点（不含），UTC
 * @param conflicts            与其他已发布计划的人员冲突列表（左闭右开重叠；相邻端点不算冲突）
 */
public record CrewCoverageView(String memberKey, String memberName, Integer qualificationVersion,
                               Integer gradeLevel, Instant validFromUtc, Instant validToUtc,
                               boolean intervalCovered, boolean gradeSufficient, boolean occupied,
                               Instant windowStartUtc, Instant windowEndUtc,
                               List<CrewConflictView> conflicts) {
}
