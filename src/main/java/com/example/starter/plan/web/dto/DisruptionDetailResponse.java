package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 封锁切换单完整视图：切换单、一对一映射与切换前后占用快照。
 * REGISTERED 时前后占用按当前实时状态构建；ACTIVE 时返回激活时写入的不可变快照。
 *
 * @param disruption  切换单视图
 * @param mappings    旧计划到替代计划的映射，按旧计划业务键升序
 * @param beforePlans 切换前旧计划快照（激活后为 SUSPENDED，历史占用保留）
 * @param afterPlans  切换后替代计划快照（激活后为 PUBLISHED，时隙生效）
 */
public record DisruptionDetailResponse(DisruptionSwitchView disruption,
                                       List<DisruptionMappingView> mappings,
                                       List<DisruptionPlanSnapshotView> beforePlans,
                                       List<DisruptionPlanSnapshotView> afterPlans) {
}
