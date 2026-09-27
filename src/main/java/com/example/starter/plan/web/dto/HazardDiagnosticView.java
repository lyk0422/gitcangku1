package com.example.starter.plan.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 危险品联合校验诊断视图：对计划当前编组与完整区段路径做只读校验，
 * 不改变任何状态。conclusion 为 PASSED 或 FAILED；conflicts 列出全部冲突对。
 */
public record HazardDiagnosticView(String scheduleKey, String conclusion,
                                   List<HazardCheckView> checks,
                                   List<Map<String, Object>> conflicts) {
}
