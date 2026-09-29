package com.example.starter.plan.model;

import java.util.List;

/**
 * 封锁登记时一条受影响计划的处理记录（登记后不可变），
 * 携带该计划与封锁窗口相交的原占用快照（原区段、原时段，左闭右开）。
 *
 * @param id              主键
 * @param blockId         所属封锁 id，关联 {@link WorkBlock#id()}
 * @param planId          受影响计划 id（登记时为已发布），关联 rail_day_plan.id
 * @param scheduleKey     受影响计划业务键快照，后续改签不改变此值
 * @param disposition     处理结果：撤销 / 整单改签
 * @param successorPlanId 改签后继计划 id；撤销时为 null
 * @param slots           与封锁相交的原占用快照，按开始时刻升序
 */
public record AffectedPlanRecord(long id, long blockId, long planId, String scheduleKey,
                                 AffectedPlanDisposition disposition, Long successorPlanId,
                                 List<Occupancy> slots) {
}
