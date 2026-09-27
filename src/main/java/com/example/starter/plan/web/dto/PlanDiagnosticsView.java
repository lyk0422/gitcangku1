package com.example.starter.plan.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 计划危险品隔离诊断视图（只读）：以当前规则集与区段开放清单，对计划当前编组与区段路径
 * 执行与发布相同的校验。result 为 PASS / FAIL；conflicts 列出全部冲突对，查询不修改状态。
 */
public record PlanDiagnosticsView(String scheduleKey,
                                  int planVersion,
                                  String status,
                                  List<ConsistCarView> cars,
                                  int ruleVersion,
                                  String rulesHash,
                                  List<String> pathSections,
                                  List<SnapshotSectionView> sectionRestrictions,
                                  List<Map<String, Object>> conflicts,
                                  String result) {
}
